package com.h.backend.skill.infrastructure.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 按 digest 寻址的只读本地制品缓存（设计 §14.4）。
 *
 * <p>缓存是派生数据：文件名只使用 digest（不使用任何用户输入路径），
 * 读取时重新核对 size；命中失败视为 miss 并隔离坏文件。写入先落
 * {@code .tmp-} 前缀临时文件再原子 rename。容量超限时按 LRU（atime 近似：
 * 每次命中 touch mtime）淘汰。缓存目录可随时整体删除。
 */
public class SkillArtifactCache {

    private static final Logger log = LoggerFactory.getLogger(SkillArtifactCache.class);

    private static final String TMP_PREFIX = ".tmp-";

    private final Path directory;
    private final long maxBytes;

    public SkillArtifactCache(Path directory, long maxBytes) {
        this.directory = directory;
        this.maxBytes = maxBytes;
    }

    /** 缓存命中且 size 校验一致时返回字节，否则返回 null。 */
    public byte[] readIfValid(String digest, long expectedSize) {
        Path file = cacheFile(digest);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            byte[] content = Files.readAllBytes(file);
            if (content.length != expectedSize) {
                quarantine(file, digest);
                return null;
            }
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
            return content;
        } catch (IOException ex) {
            log.debug("Skill artifact cache 读取失败 digest={}", digest, ex);
            return null;
        }
    }

    /** 原子写入缓存；失败只降级（后续读取回源 MinIO），不影响主流程。 */
    public void store(String digest, byte[] content) {
        try {
            Files.createDirectories(directory);
            Path tmp = directory.resolve(TMP_PREFIX + digestFileName(digest));
            Files.write(tmp, content);
            try {
                Files.move(tmp, cacheFile(digest), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
            evictIfNeeded();
        } catch (IOException ex) {
            log.debug("Skill artifact cache 写入失败 digest={}", digest, ex);
        }
    }

    private Path cacheFile(String digest) {
        return directory.resolve(digestFileName(digest));
    }

    private static String digestFileName(String digest) {
        // digest 形如 sha256:<hex>；文件名剥离前缀，只保留 hex，防御路径注入。
        String hex = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("非法 digest 形态");
        }
        return hex + ".skill.tar";
    }

    /** 命中校验失败：把坏文件挪到隔离名，避免反复读取同一损坏缓存。 */
    private void quarantine(Path file, String digest) {
        try {
            Files.move(file, directory.resolve(".bad-" + digestFileName(digest)),
                    StandardCopyOption.REPLACE_EXISTING);
            log.warn("Skill artifact cache 命中损坏，已隔离 digest={}", digest);
        } catch (IOException ex) {
            log.debug("Skill artifact cache 隔离失败 digest={}", digest, ex);
        }
    }

    /** LRU 淘汰到容量上限以内；失败静默（缓存只是派生数据）。 */
    private void evictIfNeeded() {
        try (Stream<Path> files = Files.list(directory)) {
            var entries = files.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(TMP_PREFIX))
                    .map(path -> {
                        try {
                            return new CacheEntry(path, Files.size(path),
                                    Files.getLastModifiedTime(path).toInstant());
                        } catch (IOException ex) {
                            return null;
                        }
                    })
                    .filter(entry -> entry != null)
                    .sorted(Comparator.comparing(CacheEntry::lastModified))
                    .toList();
            long total = entries.stream().mapToLong(CacheEntry::size).sum();
            int index = 0;
            while (total > maxBytes && index < entries.size()) {
                CacheEntry victim = entries.get(index++);
                Files.deleteIfExists(victim.path());
                total -= victim.size;
                log.debug("Skill artifact cache LRU 淘汰 {}", victim.path().getFileName());
            }
        } catch (IOException ex) {
            log.debug("Skill artifact cache 淘汰失败", ex);
        }
    }

    private record CacheEntry(Path path, long size, Instant lastModified) {
    }
}

package com.h.backend.skill.domain.tar;

import com.h.backend.skill.domain.SkillFileSet;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SkillTarReader {

    private static final int BLOCK_SIZE = 512;
    private static final int MAX_FILES = 1000;
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    public record ParsedBundle(SkillFileSet files, SkillBundleManifest manifest) {
    }

    private final ObjectMapper objectMapper;

    public SkillTarReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedBundle parse(byte[] tarBytes) {
        try {
            Map<String, byte[]> files = readEntries(tarBytes);
            byte[] manifestBytes = files.remove(SkillBundleManifest.MANIFEST_PATH);
            if (manifestBytes == null) {
                throw new IllegalStateException("Skill bundle 缺少 manifest.json");
            }
            SkillBundleManifest manifest = objectMapper.readValue(
                    new String(manifestBytes, StandardCharsets.UTF_8), SkillBundleManifest.class);
            verifyManifest(manifest, files);
            return new ParsedBundle(SkillFileSet.of(files), manifest);
        } catch (IOException ex) {
            throw new IllegalStateException("Skill bundle 解析失败", ex);
        }
    }

    private Map<String, byte[]> readEntries(byte[] tarBytes) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        InputStream in = new ByteArrayInputStream(tarBytes);
        byte[] header = new byte[BLOCK_SIZE];
        long total = 0;
        while (true) {
            if (!readFully(in, header)) {
                throw new EOFException("Skill bundle 在头部截断");
            }
            if (isZeroBlock(header)) {
                break;
            }
            verifyChecksum(header);
            if (header[156] != '0') {
                throw new IllegalStateException("Skill bundle 包含非常规文件条目");
            }
            String path = readPath(header);
            validatePath(path);
            if (files.containsKey(path)) {
                throw new IllegalStateException("Skill bundle 包含重复路径: " + path);
            }
            long size = readOctal(header, 124, 12);
            if (size < 0 || size > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("Skill bundle 条目大小非法");
            }
            if (files.size() + 1 > MAX_FILES) {
                throw new IllegalStateException("Skill bundle 文件数超限");
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) {
                throw new IllegalStateException("Skill bundle 总大小超限");
            }
            byte[] content = new byte[(int) size];
            if (!readFully(in, content)) {
                throw new EOFException("Skill bundle 在内容处截断");
            }
            skipPadding(in, size);
            files.put(path, content);
        }
        return files;
    }

    private void verifyManifest(SkillBundleManifest manifest, Map<String, byte[]> files) {
        if (manifest.schemaVersion() != SkillBundleManifest.SCHEMA_VERSION) {
            throw new IllegalStateException("Skill bundle schema 版本不支持");
        }
        Set<String> manifestPaths = new HashSet<>();
        for (SkillBundleManifest.Entry entry : manifest.files()) {
            if (!manifestPaths.add(entry.path())) {
                throw new IllegalStateException("Skill manifest 包含重复路径: " + entry.path());
            }
            byte[] content = files.get(entry.path());
            if (content == null) {
                throw new IllegalStateException("Skill manifest 与 bundle 内容不一致: " + entry.path());
            }
            if (content.length != entry.size()
                    || !DeterministicSkillTarBuilder.sha256Hex(content).equals(entry.sha256())) {
                throw new IllegalStateException("Skill manifest 校验失败: " + entry.path());
            }
        }
        for (String path : files.keySet()) {
            if (!manifestPaths.contains(path)) {
                throw new IllegalStateException("Skill bundle 包含 manifest 未登记的文件: " + path);
            }
        }
    }

    private boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private void skipPadding(InputStream in, long size) throws IOException {
        int padding = (int) (size % BLOCK_SIZE == 0 ? 0 : BLOCK_SIZE - (size % BLOCK_SIZE));
        long skipped = 0;
        while (skipped < padding) {
            long value = in.skip(padding - skipped);
            if (value <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("Skill bundle 在填充处截断");
                }
                skipped++;
            } else {
                skipped += value;
            }
        }
    }

    private boolean isZeroBlock(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private void verifyChecksum(byte[] header) {
        long stored = readOctal(header, 148, 8);
        long computed = 0;
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if (i >= 148 && i < 156) {
                computed += ' ';
            } else {
                computed += header[i] & 0xFF;
            }
        }
        if (stored != computed) {
            throw new IllegalStateException("Skill bundle 头部校验和不匹配");
        }
    }

    private String readPath(byte[] header) {
        int nameEnd = 0;
        while (nameEnd < 100 && header[nameEnd] != 0) {
            nameEnd++;
        }
        String name = new String(header, 0, nameEnd, StandardCharsets.UTF_8);
        int prefixEnd = 345;
        while (prefixEnd < 500 && header[prefixEnd] != 0) {
            prefixEnd++;
        }
        if (prefixEnd > 345) {
            String prefix = new String(header, 345, prefixEnd - 345, StandardCharsets.UTF_8);
            name = prefix + "/" + name;
        }
        return name;
    }

    private void validatePath(String path) {
        if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("..")
                || path.contains("//") || path.indexOf(0) >= 0 || path.contains(":")) {
            throw new IllegalStateException("Skill bundle 包含非法路径: " + path);
        }
    }

    private long readOctal(byte[] header, int offset, int length) {
        long value = 0;
        boolean started = false;
        for (int i = 0; i < length; i++) {
            byte b = header[offset + i];
            if (b == 0 || b == ' ') {
                if (started) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new IllegalStateException("Skill bundle 头部包含非法八进制字段");
            }
            started = true;
            value = value * 8 + (b - '0');
        }
        return value;
    }
}

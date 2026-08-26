package com.h.backend.chat.infrastructure.storage;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * 过渡期本地文件实现（计划任务 1 最小适配；任务 5 整体删除）。
 *
 * <ul>
 *   <li>{@link #open}：stat 后定位 FileChannel 读取区间，按
 *       {@link ResourceContent} 新契约返回 offset/responseLength/partial。</li>
 *   <li>{@link #discard}：删除本地文件，不存在时幂等。</li>
 *   <li>{@link #save}：沿用 .part + ATOMIC_MOVE 与 ImageIO 宽高探测；
 *       大小上限改按 {@link ResourceSaveCommand#effectiveMaxBytes()} 执行，
 *       超限映射 {@link ResourceStorageErrorKind#SIZE_LIMIT}。</li>
 *   <li>save 返回 {@code LOCAL_FILE}：过渡期遗留值，生产 Adapter 固定返回
 *       {@code OBJECT_STORAGE}（计划 §4.1/不变量 4）。</li>
 *   <li>URL 构造职责已移除（计划 §2.4.3/§4.4），由应用层
 *       {@code ChatResourceUrls} 承担；{@code image-generation.storage.public-base-url}
 *       不再被存储层使用，任务 5 一并清理配置。</li>
 * </ul>
 */
@Component
public class LocalFileResourceStorage implements ResourceStorage {

    private static final String STORAGE_TYPE = "LOCAL_FILE";

    private final Path baseDir;

    public LocalFileResourceStorage(ImageGenerationProperties properties) {
        ImageGenerationProperties.LocalStorage storage = properties.storageOrDefault();
        this.baseDir = Path.of(storage.baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public StoredResource save(ResourceSaveCommand command) {
        long maxBytes = command.effectiveMaxBytes();
        if (command.declaredSize() != null && command.declaredSize() > maxBytes) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.SIZE_LIMIT, "资源声明大小超过存储上限");
        }
        String resourceId = UUID.randomUUID().toString();
        String extension = normalizeExtension(command.extension(), command.mimeType());
        ResourceLocation location = resourceLocation(command.resourceType());
        Path target = targetPath(resourceId, extension, location);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");

        try {
            Files.createDirectories(target.getParent());
            long size;
            try (InputStream contentStream = command.openContentStream()) {
                size = copyWithLimit(contentStream, temporary, maxBytes);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            ImageSize imageSize = readImageSize(command.content());
            return new StoredResource(
                    resourceId,
                    STORAGE_TYPE,
                    baseDir.relativize(target).toString(),
                    storedResourceMimeType(command.mimeType()),
                    "%s-%s.%s".formatted(location.filePrefix(), resourceId, extension),
                    size,
                    command.width() == null ? imageSize.width() : command.width(),
                    command.height() == null ? imageSize.height() : command.height()
            );
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "保存资源失败", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 成功路径 temporary 已被 move；失败路径尽力清理 .part 残留。
            }
        }
    }

    @Override
    public ResourceContent open(String storageKey, ResourceRange range) {
        Path target = resolveTarget(storageKey);
        long totalSize;
        try {
            totalSize = Files.size(target);
        } catch (NoSuchFileException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.NOT_FOUND, "资源不存在或已被清理", exception);
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "读取资源失败", exception);
        }
        ResourceRange.Resolved resolved = range.resolve(totalSize);
        try {
            FileChannel channel = FileChannel.open(target, StandardOpenOption.READ);
            channel.position(resolved.offset());
            InputStream inputStream = new BoundedInputStream(
                    Channels.newInputStream(channel), resolved.length());
            String mimeType = probeMimeType(target);
            return new ResourceContent(
                    inputStream,
                    mimeType,
                    totalSize,
                    resolved.length(),
                    resolved.offset(),
                    resolved.partial()
            );
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "读取资源失败", exception);
        }
    }

    @Override
    public void discard(String storageKey) {
        Path target = resolveTarget(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "删除资源失败", exception);
        }
    }

    private Path resolveTarget(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid resource storage path");
        }
        return target;
    }

    private String probeMimeType(Path target) {
        try {
            String mimeType = Files.probeContentType(target);
            return mimeType == null ? "application/octet-stream" : mimeType;
        } catch (IOException exception) {
            return "application/octet-stream";
        }
    }

    private ResourceLocation resourceLocation(String resourceType) {
        if ("AUDIO".equalsIgnoreCase(resourceType)) {
            return new ResourceLocation("call-audio", "call-audio");
        }
        if ("VIDEO".equalsIgnoreCase(resourceType)) {
            return new ResourceLocation("generated-videos", "video");
        }
        if ("FILE".equalsIgnoreCase(resourceType) || "DOCUMENT".equalsIgnoreCase(resourceType)) {
            return new ResourceLocation("generated-files", "file");
        }
        return new ResourceLocation("generated-images", "generated");
    }

    private Path targetPath(String resourceId, String extension, ResourceLocation location) {
        LocalDate today = LocalDate.now();
        String relativeKey = "%s/%04d/%02d/%02d/%s.%s".formatted(
                location.directory(), today.getYear(), today.getMonthValue(), today.getDayOfMonth(), resourceId, extension
        );
        Path target = baseDir.resolve(relativeKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid resource storage path");
        }
        return target;
    }

    private long copyWithLimit(InputStream inputStream, Path target, long maxBytes) throws IOException {
        long written = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream outputStream = Files.newOutputStream(target)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw new ResourceStorageException(
                            ResourceStorageErrorKind.SIZE_LIMIT, "资源超过存储大小上限");
                }
                outputStream.write(buffer, 0, read);
            }
        }
        return written;
    }

    private String normalizeExtension(String extension, String mimeType) {
        if (extension != null && !extension.isBlank()) {
            return extension.replace(".", "").toLowerCase(Locale.ROOT);
        }
        if ("image/jpeg".equalsIgnoreCase(mimeType)) {
            return "jpg";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return "webp";
        }
        if ("audio/webm".equalsIgnoreCase(mimeType)) {
            return "webm";
        }
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return "mp3";
        }
        if ("audio/mp4".equalsIgnoreCase(mimeType)) {
            return "m4a";
        }
        if ("audio/wav".equalsIgnoreCase(mimeType) || "audio/x-wav".equalsIgnoreCase(mimeType)) {
            return "wav";
        }
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return "png";
        }
        return "bin";
    }

    private ImageSize readImageSize(byte[] content) throws IOException {
        if (content == null) {
            return new ImageSize(null, null);
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
        if (image == null) {
            return new ImageSize(null, null);
        }
        return new ImageSize(image.getWidth(), image.getHeight());
    }

    private String storedResourceMimeType(String mimeType) {
        return mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType;
    }

    private record ResourceLocation(String directory, String filePrefix) {
    }

    private record ImageSize(Integer width, Integer height) {
    }

    /** 只放行本次响应区间内的字节，保证 partial 流在 responseLength 处结束。 */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}

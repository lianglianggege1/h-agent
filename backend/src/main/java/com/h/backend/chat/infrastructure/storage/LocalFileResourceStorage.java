package com.h.backend.chat.infrastructure.storage;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Component
public class LocalFileResourceStorage implements ResourceStorage {

    private static final String STORAGE_TYPE = "LOCAL_FILE";

    private final Path baseDir;
    private final String publicBaseUrl;

    public LocalFileResourceStorage(ImageGenerationProperties properties) {
        ImageGenerationProperties.LocalStorage storage = properties.storageOrDefault();
        this.baseDir = Path.of(storage.baseDir()).toAbsolutePath().normalize();
        this.publicBaseUrl = storage.publicBaseUrl() == null ? "" : storage.publicBaseUrl().stripTrailing();
    }

    @Override
    public StoredResource save(ResourceSaveCommand command) {
        String resourceId = UUID.randomUUID().toString();
        String extension = normalizeExtension(command.extension(), command.mimeType());
        ResourceLocation location = resourceLocation(command.resourceType());
        Path target = targetPath(resourceId, extension, location);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");

        try {
            Files.createDirectories(target.getParent());
            long size;
            try (InputStream contentStream = command.openContentStream()) {
                size = copyWithLimit(contentStream, temporary, command.maxBytes());
            }
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The original storage error is more useful to the caller.
            }
            throw new IllegalStateException("Failed to save generated resource", exception);
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
                if (maxBytes > 0 && written > maxBytes) {
                    throw new IOException("Resource exceeds configured maximum size");
                }
                outputStream.write(buffer, 0, read);
            }
        }
        return written;
    }

    @Override
    public ResourceContent open(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid resource storage path");
        }
        try {
            String mimeType = Files.probeContentType(target);
            InputStream inputStream = Files.newInputStream(target);
            return new ResourceContent(inputStream, mimeType == null ? "application/octet-stream" : mimeType, Files.size(target));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to open generated resource", ex);
        }
    }

    @Override
    public String buildViewUrl(String resourceId) {
        return publicBaseUrl + "/api/chat/resources/" + resourceId + "/content";
    }

    @Override
    public String buildDownloadUrl(String resourceId) {
        return publicBaseUrl + "/api/chat/resources/" + resourceId + "/download";
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
}

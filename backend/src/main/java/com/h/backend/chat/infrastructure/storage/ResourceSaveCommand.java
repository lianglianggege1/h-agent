package com.h.backend.chat.infrastructure.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Objects;

public final class ResourceSaveCommand {
    private final String resourceType;
    private final String sessionId;
    private final String prompt;
    private final byte[] content;
    private final InputStream contentStream;
    private final String mimeType;
    private final String extension;
    private final Integer width;
    private final Integer height;
    private final long maxBytes;

    public ResourceSaveCommand(
            String resourceType,
            String sessionId,
            String prompt,
            byte[] content,
            String mimeType,
            String extension,
            Integer width,
            Integer height
    ) {
        this(resourceType, sessionId, prompt, content, null, mimeType, extension, width, height, 0);
    }

    public static ResourceSaveCommand fromStream(
            String resourceType,
            String sessionId,
            String prompt,
            InputStream contentStream,
            String mimeType,
            String extension,
            long maxBytes
    ) {
        return new ResourceSaveCommand(
                resourceType, sessionId, prompt, null, Objects.requireNonNull(contentStream, "contentStream must not be null"),
                mimeType, extension, null, null, maxBytes
        );
    }

    private ResourceSaveCommand(
            String resourceType,
            String sessionId,
            String prompt,
            byte[] content,
            InputStream contentStream,
            String mimeType,
            String extension,
            Integer width,
            Integer height,
            long maxBytes
    ) {
        this.resourceType = resourceType;
        this.sessionId = sessionId;
        this.prompt = prompt;
        this.content = content;
        this.contentStream = contentStream;
        this.mimeType = mimeType;
        this.extension = extension;
        this.width = width;
        this.height = height;
        this.maxBytes = maxBytes;
    }

    public String resourceType() { return resourceType; }
    public String sessionId() { return sessionId; }
    public String prompt() { return prompt; }
    public byte[] content() { return content; }
    public String mimeType() { return mimeType; }
    public String extension() { return extension; }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public long maxBytes() { return maxBytes; }

    public InputStream openContentStream() {
        return contentStream == null ? new ByteArrayInputStream(content == null ? new byte[0] : content) : contentStream;
    }
}

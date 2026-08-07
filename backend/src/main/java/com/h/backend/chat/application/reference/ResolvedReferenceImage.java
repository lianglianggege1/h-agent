package com.h.backend.chat.application.reference;

import java.util.Objects;

public record ResolvedReferenceImage(
        String resourceId,
        String mimeType,
        byte[] content,
        long fileSize,
        Integer width,
        Integer height
) {
    public ResolvedReferenceImage {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
        if (mimeType == null || mimeType.isBlank()) {
            throw new IllegalArgumentException("mimeType must not be blank");
        }
        content = Objects.requireNonNull(content, "content must not be null").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}

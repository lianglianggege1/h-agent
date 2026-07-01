package com.h.backend.chat.infrastructure.storage;

public record ResourceSaveCommand(
        String resourceType,
        String sessionId,
        String prompt,
        byte[] content,
        String mimeType,
        String extension,
        Integer width,
        Integer height
) {
}

package com.h.backend.chat.storage;

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

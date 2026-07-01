package com.h.backend.chat.infrastructure.storage;

public record StoredResource(
        String id,
        String storageType,
        String storageKey,
        String mimeType,
        String fileName,
        Long fileSize,
        Integer width,
        Integer height
) {
}

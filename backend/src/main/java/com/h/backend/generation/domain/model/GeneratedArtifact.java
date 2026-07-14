package com.h.backend.generation.domain.model;

public record GeneratedArtifact(
        String resourceId,
        String storageType,
        String storageKey,
        String mimeType,
        String fileName,
        long fileSize
) {
    public GeneratedArtifact {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId must not be blank");
        }
    }
}

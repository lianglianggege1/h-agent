package com.h.backend.chat.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ChatMessageResourceDto(
        String id,
        String type,
        String role,
        String viewUrl,
        String downloadUrl,
        String fileName,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height,
        Object metadata,
        @JsonIgnore String storageType,
        @JsonIgnore String storageKey
) {
    public ChatMessageResourceDto(
            String id,
            String type,
            String role,
            String viewUrl,
            String downloadUrl,
            String fileName,
            String mimeType,
            Long fileSize,
            Integer width,
            Integer height,
            String storageType,
            String storageKey
    ) {
        this(id, type, role, viewUrl, downloadUrl, fileName, mimeType, fileSize, width, height, null, storageType, storageKey);
    }

    public ChatMessageResourceDto(
            String id,
            String type,
            String role,
            String viewUrl,
            String downloadUrl,
            String fileName,
            String mimeType,
            Long fileSize,
            Integer width,
            Integer height
    ) {
        this(id, type, role, viewUrl, downloadUrl, fileName, mimeType, fileSize, width, height, null, null, null);
    }
}

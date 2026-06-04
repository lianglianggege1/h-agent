package com.h.backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record ChatMessageResourceDto(
        String id,
        String kind,
        String viewUrl,
        String downloadUrl,
        String fileName,
        String mimeType,
        Long fileSize,
        Integer width,
        Integer height,
        @JsonIgnore String storageType,
        @JsonIgnore String storageKey,
        @JsonIgnore String sha256
) {
    public ChatMessageResourceDto(
            String id,
            String kind,
            String viewUrl,
            String downloadUrl,
            String fileName,
            String mimeType,
            Long fileSize,
            Integer width,
            Integer height
    ) {
        this(id, kind, viewUrl, downloadUrl, fileName, mimeType, fileSize, width, height, null, null, null);
    }
}

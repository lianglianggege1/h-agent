package com.h.backend.chat.dto;

public record ResourceUploadResponse(
    String resourceId,
    String kind,
    String viewUrl,
    String downloadUrl,
    String fileName,
    String mimeType,
    Long fileSize
) {}

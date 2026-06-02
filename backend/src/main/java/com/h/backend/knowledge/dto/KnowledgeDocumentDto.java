package com.h.backend.knowledge.dto;

import java.time.LocalDateTime;

public record KnowledgeDocumentDto(
        Long id,
        String fileName,
        String sourceType,
        String fileType,
        Long fileSize,
        Integer charCount,
        Integer segmentCount,
        String status,
        String errorMsg,
        LocalDateTime createdAt
) {}

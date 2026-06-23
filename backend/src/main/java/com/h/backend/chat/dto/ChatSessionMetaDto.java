package com.h.backend.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionMetaDto(
        String sessionId,
        String title,
        Long promptId,
        String agentId,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived
) {
}

package com.h.backend.chat.dto;

import java.time.LocalDateTime;

public record ChatSessionSummaryDto(
        String sessionId,
        String title,
        String lastUserMessage,
        Long promptId,
        String agentId,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived
) {
}

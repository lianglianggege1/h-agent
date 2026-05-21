package com.h.backend.chat.model;

import java.time.LocalDateTime;

public record AgentRunSummary(
        Long id,
        String status,
        Long assistantMessageId,
        int toolCount,
        String toolNamesJson,
        String errorMessage,
        LocalDateTime completedAt
) {
}

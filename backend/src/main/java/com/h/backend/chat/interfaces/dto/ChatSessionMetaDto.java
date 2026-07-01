package com.h.backend.chat.interfaces.dto;

import java.time.LocalDateTime;

public record ChatSessionMetaDto(
        String sessionId,
        String title,
        Long promptId,
        String agentId,
        String agentDisplayName,
        String agentDomain,
        String runtimeType,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived
) {
}

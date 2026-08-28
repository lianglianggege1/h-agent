package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.approval.ApprovalMode;
import java.time.LocalDateTime;

public record ChatSessionMetaDto(
        String sessionId,
        String title,
        Long promptId,
        String agentId,
        String agentDisplayName,
        String agentDomain,
        String runtimeType,
        ApprovalMode approvalMode,
        int messageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean archived
) {
}

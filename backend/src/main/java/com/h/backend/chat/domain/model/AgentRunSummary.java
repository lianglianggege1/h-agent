package com.h.backend.chat.domain.model;

import com.h.backend.chat.domain.approval.ApprovalMode;

import java.time.LocalDateTime;

public record AgentRunSummary(
        Long id,
        String status,
        Long assistantMessageId,
        int toolCount,
        String toolNamesJson,
        String errorMessage,
        LocalDateTime completedAt,
        String sessionId,
        Long userId,
        Long promptId,
        Long userMessageId,
        String modelName,
        ApprovalMode approvalMode,
        String traceParent
) {
    public AgentRunSummary(
            Long id, String status, Long assistantMessageId, int toolCount,
            String toolNamesJson, String errorMessage, LocalDateTime completedAt
    ) {
        this(id, status, assistantMessageId, toolCount, toolNamesJson, errorMessage,
                completedAt, null, null, null, null, null, null, null);
    }
}

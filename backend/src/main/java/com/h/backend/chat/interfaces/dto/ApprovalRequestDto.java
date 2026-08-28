package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.domain.approval.ApprovalRequestStatus;

import java.time.LocalDateTime;
import java.util.List;

public record ApprovalRequestDto(
        String approvalId,
        Long runId,
        String rootSessionId,
        String sessionId,
        String subagentExecutionId,
        ApprovalMode approvalMode,
        List<ApprovalActionDto> actions,
        ApprovalRequestStatus status,
        ApprovalDecision decision,
        int version,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt
) {
}

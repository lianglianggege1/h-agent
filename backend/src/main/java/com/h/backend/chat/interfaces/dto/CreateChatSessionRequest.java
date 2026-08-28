package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.approval.ApprovalMode;

public record CreateChatSessionRequest(
        String currentSessionId,
        Long promptId,
        String agentId,
        ApprovalMode approvalMode
) {
}

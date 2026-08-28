package com.h.backend.chat.application;

import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.domain.approval.ApprovalEpisode;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;

import java.util.List;

public interface ApprovalRequestService {

    ApprovalRequestDto suspend(SuspendApprovalCommand command);

    ApprovalRequestDto findPending(Long userId, String sessionId);

    ApprovalRequestDto getOwned(Long userId, String approvalId);

    ApprovalResolution decide(Long userId, String approvalId, ApprovalDecision decision);

    record SuspendApprovalCommand(
            Long runId,
            Long userId,
            String rootSessionId,
            String sessionId,
            String subagentExecutionId,
            ApprovalMode approvalMode,
            ApprovalEpisode episode
    ) {
    }

    record ApprovalResolution(
            ApprovalRequestDto request,
            List<String> toolCallIds,
            boolean approved
    ) {
    }
}

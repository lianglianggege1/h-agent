package com.h.backend.chat.application;

import com.h.backend.chat.domain.model.AgentRunSummary;
import com.h.backend.chat.domain.approval.ApprovalMode;

public interface AgentRunService {

    AgentRunHandle createRun(
            String sessionId,
            Long userId,
            Long promptId,
            Long userMessageId,
            String modelName,
            String langfuseTraceId
    );

    void updateTraceId(Long runId, String langfuseTraceId);

    default void bindApprovalContext(Long runId, ApprovalMode approvalMode, String traceParent) {
    }

    default boolean hasOpenRun(String sessionId) {
        return false;
    }

    default boolean transitionStatus(Long runId, String expectedStatus, String nextStatus) {
        return false;
    }

    void recordToolUsage(Long runId, String toolName);

    void completeRun(Long runId, Long assistantMessageId);

    void failRun(Long runId, String errorMessage);

    AgentRunSummary getById(Long runId);

    record AgentRunHandle(Long id) {
    }
}

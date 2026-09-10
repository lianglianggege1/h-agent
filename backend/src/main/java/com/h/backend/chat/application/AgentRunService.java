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

    /**
     * 返回当前会话中唯一开放的顶级 AgentRun（RUNNING 或 WAITING_APPROVAL）。
     * 用于工具调用时确定提案应关联的 Run；找不到或存在多个时抛错，绝不猜测最近消息。
     * 调用方无需理解开放状态、会话所有权与多行处理。
     */
    AgentRunSummary requireOpenRun(Long userId, String sessionId);

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

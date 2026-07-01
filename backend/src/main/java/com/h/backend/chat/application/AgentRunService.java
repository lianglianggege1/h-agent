package com.h.backend.chat.application;

import com.h.backend.chat.domain.model.AgentRunSummary;

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

    void recordToolUsage(Long runId, String toolName);

    void completeRun(Long runId, Long assistantMessageId);

    void failRun(Long runId, String errorMessage);

    AgentRunSummary getById(Long runId);

    record AgentRunHandle(Long id) {
    }
}

package com.h.backend.chat.service;

public interface AgentRunService {

    AgentRunHandle createRun(
            String sessionId,
            Long userId,
            Long promptId,
            Long userMessageId,
            String modelName,
            String langfuseTraceId
    );

    record AgentRunHandle(Long id) {
    }
}

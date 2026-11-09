package com.h.backend.memory.application;

import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;

/** Trusted, per-execution identity. Never populated from model arguments. */
public record AgentMemoryContext(Long userId, String rootSessionId, String sessionId,
                                 String stableAgentId, String actor, Long sourceMessageId,
                                 String executionId) {
    public static final String KEY = "h-agent.agent-memory-context";

    public AgentMemoryContext {
        if (userId == null || rootSessionId == null || sessionId == null || executionId == null)
            throw new IllegalArgumentException("Trusted memory execution context is required");
    }

    public static AgentMemoryContext from(ChatAgentExecutionCommand command) {
        return new AgentMemoryContext(command.userId(), command.rootSessionId(), command.sessionId(),
                command.agent().agentId(), command.agent().agentId(), command.userMessageId(),
                command.runHandle().id().toString());
    }
}

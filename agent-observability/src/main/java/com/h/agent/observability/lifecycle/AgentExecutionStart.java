package com.h.agent.observability.lifecycle;

import com.h.agent.observability.semantic.SemanticContent;

import java.util.List;
import java.util.Map;

public record AgentExecutionStart(
        String traceName,
        String sessionId,
        Long userId,
        String agentId,
        String agentSessionId,
        String entryKind,
        String rootRunId,
        List<String> tags,
        Map<String, String> attributes,
        SemanticContent input
) {
}

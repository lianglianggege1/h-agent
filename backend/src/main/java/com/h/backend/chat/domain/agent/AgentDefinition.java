package com.h.backend.chat.domain.agent;

import java.util.List;

public record AgentDefinition(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        Object agentBean,
        AgentRuntimeType runtimeType,
        boolean enabled
) {
    public AgentDefinition {
        tags = List.copyOf(tags);
    }
}

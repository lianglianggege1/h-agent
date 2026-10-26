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
        boolean enabled,
        boolean harnessMemoryEnabled
) {
    public AgentDefinition(String agentId, String displayName, String domain, List<String> tags,
                           String summary, Object agentBean, AgentRuntimeType runtimeType, boolean enabled) {
        this(agentId, displayName, domain, tags, summary, agentBean, runtimeType, enabled, false);
    }

    public AgentDefinition {
        tags = List.copyOf(tags);
    }

    public boolean supportsHarnessMemory() {
        return enabled && runtimeType == AgentRuntimeType.HARNESS_STREAMING && harnessMemoryEnabled;
    }
}

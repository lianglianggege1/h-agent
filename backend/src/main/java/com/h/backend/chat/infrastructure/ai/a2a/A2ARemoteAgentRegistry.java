package com.h.backend.chat.infrastructure.ai.a2a;

import dev.langchain4j.agentic.internal.AgentExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = "enabled", havingValue = "true", matchIfMissing = true)
public class A2ARemoteAgentRegistry {

    private final Map<String, AgentExecutor> agents = new LinkedHashMap<>();

    public A2ARemoteAgentRegistry() {
    }

    public AgentExecutor require(String id) {
        AgentExecutor agent = agent(id);
        if (agent == null) {
            throw new IllegalArgumentException("Remote A2A agent is not configured: " + id);
        }
        return agent;
    }

    public List<AgentExecutor> all() {
        ensureLoaded();
        return List.copyOf(agents.values());
    }

    private AgentExecutor agent(String id) {
        ensureLoaded();
        return agents.get(id);
    }

    private synchronized void ensureLoaded() {
        // Agents are no longer pre-configured via remote-agents list.
        // They are registered dynamically using the unified /a2a/agents/{agentId} endpoint pattern.
    }
}

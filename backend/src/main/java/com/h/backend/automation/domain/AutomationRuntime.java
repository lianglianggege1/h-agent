package com.h.backend.automation.domain;

import com.h.backend.chat.domain.agent.AgentRuntimeType;

public enum AutomationRuntime {
    LANGCHAIN4J,
    AGENTSCOPE;

    public static AutomationRuntime forAgentRuntime(AgentRuntimeType runtimeType) {
        return runtimeType == AgentRuntimeType.HARNESS_STREAMING ? AGENTSCOPE : LANGCHAIN4J;
    }
}

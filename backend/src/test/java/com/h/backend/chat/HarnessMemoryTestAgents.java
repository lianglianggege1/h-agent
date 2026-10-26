package com.h.backend.chat;

import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.ChatAgentIds;

import java.util.List;

public final class HarnessMemoryTestAgents {
    private HarnessMemoryTestAgents() {}

    public static AgentRegistry registry() {
        return new AgentRegistry(List.of(agent(ChatAgentIds.HARNESS), agent(ChatAgentIds.ORDER_PROCESSING)));
    }

    public static AgentDefinition agent(String id) {
        return new AgentDefinition(id, id, "test", List.of(), "", new Object(),
                AgentRuntimeType.HARNESS_STREAMING, true, true);
    }
}

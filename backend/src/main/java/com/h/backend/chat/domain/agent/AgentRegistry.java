package com.h.backend.chat.domain.agent;

import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentRegistry {

    public static final String STANDARD_CHAT_AGENT_ID = ChatAgentIds.STANDARD_CHAT;

    private final Map<String, AgentDefinition> definitions;

    public AgentRegistry(List<AgentDefinition> definitions) {
        Map<String, AgentDefinition> ordered = new LinkedHashMap<>();
        for (AgentDefinition definition : definitions) {
            ordered.put(definition.agentId(), definition);
        }
        this.definitions = Collections.unmodifiableMap(ordered);
    }

    public List<AgentDefinition> listEnabled() {
        return definitions.values().stream()
                .filter(AgentDefinition::enabled)
                .toList();
    }

    public AgentDefinition requireEnabled(String agentId) {
        AgentDefinition definition = definitions.get(agentId);
        if (definition == null || !definition.enabled()) {
            throw new BusinessException(41001, "领域 Agent 不存在或未启用");
        }
        return definition;
    }
}

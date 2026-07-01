package com.h.backend.chat.domain.agent;

import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    @Test
    void listsOnlyEnabledAgentsInRegistrationOrder() {
        AgentDefinition first = new AgentDefinition(
                "a1",
                "Agent A",
                "出行",
                List.of("tag"),
                "summary",
                new Object(),
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentDefinition disabled = new AgentDefinition(
                "a2",
                "Agent B",
                "企业",
                List.of(),
                "summary",
                new Object(),
                AgentRuntimeType.AGENTIC_SYNC,
                false
        );
        AgentDefinition second = new AgentDefinition(
                "a3",
                "Agent C",
                "通用",
                List.of("chat"),
                "summary",
                new Object(),
                AgentRuntimeType.STANDARD_STREAMING_CHAT,
                true
        );

        AgentRegistry registry = new AgentRegistry(List.of(first, disabled, second));

        assertEquals(List.of("a1", "a3"), registry.listEnabled().stream()
                .map(AgentDefinition::agentId)
                .toList());
    }

    @Test
    void requireEnabledReturnsEnabledAgent() {
        AgentDefinition definition = new AgentDefinition(
                "a1",
                "Agent A",
                "出行",
                List.of("tag"),
                "summary",
                new Object(),
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentRegistry registry = new AgentRegistry(List.of(definition));

        assertSame(definition, registry.requireEnabled("a1"));
    }

    @Test
    void requireEnabledRejectsMissingOrDisabledAgent() {
        AgentDefinition disabled = new AgentDefinition(
                "a1",
                "Agent A",
                "出行",
                List.of("tag"),
                "summary",
                new Object(),
                AgentRuntimeType.AGENTIC_SYNC,
                false
        );
        AgentRegistry registry = new AgentRegistry(List.of(disabled));

        BusinessException missing = assertThrows(BusinessException.class, () -> registry.requireEnabled("missing"));
        assertEquals(41001, missing.getCode());
        assertTrue(missing.getMessage().contains("领域 Agent 不存在或未启用"));

        BusinessException disabledAgent = assertThrows(BusinessException.class, () -> registry.requireEnabled("a1"));
        assertEquals(41001, disabledAgent.getCode());
        assertTrue(disabledAgent.getMessage().contains("领域 Agent 不存在或未启用"));
    }
}

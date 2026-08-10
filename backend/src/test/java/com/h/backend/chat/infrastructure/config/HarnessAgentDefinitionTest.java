package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HarnessAgentDefinitionTest {

    @Test
    void shouldRegisterHarnessAgentAsDedicatedStreamingRuntime() {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);

        AgentDefinition definition = new AgentDefinitionConfig().harnessAgentDefinition(harnessAgent);

        assertEquals("harness-agent", definition.agentId());
        assertEquals(AgentRuntimeType.HARNESS_STREAMING, definition.runtimeType());
        assertSame(harnessAgent, definition.agentBean());
        assertTrue(definition.enabled());
    }
}

package com.h.capabilities.memory.domain;

import com.h.capabilities.chat.domain.agent.ChatAgentIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryPolicyCatalogTest {

    private final AgentMemoryPolicyCatalog catalog = new AgentMemoryPolicyCatalog();

    @Test
    void standardChatUsesExplicitMemoryToolsWithoutAutomaticRecallOrCapture() {
        AgentMemoryPolicy policy = catalog.policyOf(ChatAgentIds.STANDARD_CHAT);
        assertEquals(java.util.Set.of(), policy.recallScopes());
        assertNull(policy.automaticCaptureScope());
        assertTrue(policy.explicitMemoryToolsEnabled());
        assertFalse(policy.recallEnabled());
        assertFalse(policy.automaticCaptureEnabled());
    }

    @Test
    void visibleDomainAgentsCaptureRunScopeOnly() {
        for (String agentId : java.util.List.of("export-assistant", "car-rental-assistant", "story-chat-agent")) {
            AgentMemoryPolicy policy = catalog.policyOf(agentId);
            assertEquals(MemoryScopeKind.RUN, policy.automaticCaptureScope(), agentId);
            assertTrue(policy.recallEnabled(), agentId);
        }
    }

    @Test
    void responsiveLeavesRecallWithoutAutomaticCapture() {
        for (String agentId : java.util.List.of(
                "export-assistant.medical-expert",
                "export-assistant.legal-expert",
                "export-assistant.technical-expert",
                "car-rental-assistant.response-generator",
                "story-chat-agent.creative-writer")) {
            AgentMemoryPolicy policy = catalog.policyOf(agentId);
            assertTrue(policy.recallEnabled(), agentId);
            assertFalse(policy.automaticCaptureEnabled(), agentId);
        }
    }

    @Test
    void teamWorkersUseExplicitMem0ToolsWithoutAutomaticCapture() {
        for (String agentId : java.util.List.of(
                com.h.matrixteam.TeamAgentIds.WORKER, com.h.matrixteam.TeamAgentIds.SPRING_WORKER)) {
            AgentMemoryPolicy policy = catalog.policyOf(agentId);
            assertTrue(policy.explicitMemoryToolsEnabled(), agentId);
            assertTrue(policy.recallEnabled(), agentId);
            assertFalse(policy.automaticCaptureEnabled(), agentId);
            assertEquals(java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.SESSION),
                    policy.recallScopes(), agentId);
        }
    }

    @Test
    void unknownAndIntermediateAgentsAreDisabled() {
        for (String agentId : java.util.List.of("unknown-agent", "router", "extractor", "scorer")) {
            AgentMemoryPolicy policy = catalog.policyOf(agentId);
            assertFalse(policy.recallEnabled(), agentId);
            assertFalse(policy.automaticCaptureEnabled(), agentId);
        }
    }
}

package com.h.backend.memory.domain;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryPolicyCatalogTest {

    private final AgentMemoryPolicyCatalog catalog = new AgentMemoryPolicyCatalog();

    @Test
    void standardChatRecallsAllScopesAndCapturesUserScope() {
        AgentMemoryPolicy policy = catalog.policyOf(ChatAgentIds.STANDARD_CHAT);
        assertEquals(java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
                policy.recallScopes());
        assertEquals(MemoryScopeKind.USER, policy.automaticCaptureScope());
        assertTrue(policy.explicitMemoryToolsEnabled());
        assertTrue(policy.recallEnabled());
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
    void unknownAndIntermediateAgentsAreDisabled() {
        for (String agentId : java.util.List.of("unknown-agent", "router", "extractor", "scorer")) {
            AgentMemoryPolicy policy = catalog.policyOf(agentId);
            assertFalse(policy.recallEnabled(), agentId);
            assertFalse(policy.automaticCaptureEnabled(), agentId);
        }
    }
}

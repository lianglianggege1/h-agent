package com.h.backend.memory.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemoryScopePolicyTest {

    private final MemoryInvocationContext context = new MemoryInvocationContext(
            7L, "export-assistant", "session-root", 99L, "session-actual", null);

    @Test
    void userScopeCarriesOnlyUserId() {
        MemoryScopePolicy.MemoryOwnerScope scope =
                MemoryScopePolicy.toOwnerScope(context, MemoryScopeKind.USER);
        assertEquals("h-agent:user:7", scope.mem0UserId());
        assertNull(scope.mem0AgentId());
        assertNull(scope.mem0RunId());
        assertEquals(MemoryScopeKind.USER, scope.scopeKind());
    }

    @Test
    void agentScopeCarriesUserIdAndAgentId() {
        MemoryScopePolicy.MemoryOwnerScope scope =
                MemoryScopePolicy.toOwnerScope(context, MemoryScopeKind.AGENT);
        assertEquals("h-agent:user:7", scope.mem0UserId());
        assertEquals("h-agent:agent:export-assistant", scope.mem0AgentId());
        assertNull(scope.mem0RunId());
    }

    @Test
    void runScopeCarriesUserIdAgentIdAndRunId() {
        MemoryScopePolicy.MemoryOwnerScope scope =
                MemoryScopePolicy.toOwnerScope(context, MemoryScopeKind.RUN);
        assertEquals("h-agent:user:7", scope.mem0UserId());
        assertEquals("h-agent:agent:export-assistant", scope.mem0AgentId());
        assertEquals("h-agent:run:session-root", scope.mem0RunId());
    }

    @Test
    void recordBasedFactoryMatchesContextBasedFactory() {
        for (MemoryScopeKind kind : MemoryScopeKind.values()) {
            assertEquals(
                    MemoryScopePolicy.toOwnerScope(context, kind),
                    MemoryScopePolicy.toOwnerScope(
                            context.userId(), kind, context.logicalAgentId(), context.memoryRunId()));
        }
    }

    @Test
    void rejectsNullUserIdOrScope() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryScopePolicy.toOwnerScope(null, MemoryScopeKind.USER, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryScopePolicy.toOwnerScope(7L, null, null, null));
    }
}

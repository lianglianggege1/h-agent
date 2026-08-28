package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.approval.ApprovalMode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeApprovalAdapterTest {

    @Test
    void installsNonTrivialDefaultContextWithoutDroppingExistingRules() {
        PermissionRule denyRule = new PermissionRule(
                "dangerous",
                null,
                PermissionBehavior.DENY,
                "platform"
        );
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("session-1")
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addDenyRule("dangerous", denyRule)
                        .build())
                .build();
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getAgentState("42", "session-1")).thenReturn(state);

        AgentScopeApprovalAdapter adapter =
                new AgentScopeApprovalAdapter("/tmp/h-agent/harness-workspace");
        adapter.applyMode(agent, "42", "session-1", ApprovalMode.DEFAULT);

        ArgumentCaptor<PermissionContextState> contextCaptor =
                ArgumentCaptor.forClass(PermissionContextState.class);
        verify(agent).replacePermissionContext(
                org.mockito.ArgumentMatchers.eq("42"),
                org.mockito.ArgumentMatchers.eq("session-1"),
                contextCaptor.capture()
        );
        PermissionContextState installed = contextCaptor.getValue();
        assertFalse(installed.isTrivial());
        assertEquals(PermissionMode.DEFAULT, installed.getMode());
        assertEquals(denyRule, installed.getDenyRules().get("dangerous").getFirst());
        assertTrue(installed.getWorkingDirectories()
                .containsKey("/tmp/h-agent/harness-workspace"));
    }

    @Test
    void mapsEveryProjectModeToTheSdkMode() {
        AgentScopeApprovalAdapter adapter =
                new AgentScopeApprovalAdapter("/tmp/h-agent/harness-workspace");

        assertEquals(PermissionMode.DEFAULT, adapter.toSdk(ApprovalMode.DEFAULT));
        assertEquals(PermissionMode.ACCEPT_EDITS, adapter.toSdk(ApprovalMode.ACCEPT_EDITS));
        assertEquals(PermissionMode.EXPLORE, adapter.toSdk(ApprovalMode.EXPLORE));
        assertEquals(PermissionMode.BYPASS, adapter.toSdk(ApprovalMode.BYPASS));
        assertEquals(PermissionMode.DONT_ASK, adapter.toSdk(ApprovalMode.DONT_ASK));
    }
}

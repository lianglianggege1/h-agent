package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.approval.ApprovalMode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeHarnessRuntimeTest {

    @Test
    void shouldPersistAssignmentAndReuseTheOriginalUserSessionSlotForFollowUps() {
        HarnessAgent parent = mock(HarnessAgent.class);
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        ReActAgent child = mock(ReActAgent.class);
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("child-session")
                .build();
        HarnessSubagentContext context = new HarnessSubagentContext(
                "research-agent",
                "42",
                "parent-session",
                "child-session",
                "收集并整理官方资料",
                "execution-follow-up"
        );

        when(parent.getSubagentAgentManager()).thenReturn(manager);
        when(manager.createAgentIfPresent(eq("research-agent"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(child));
        when(child.getAgentState("42", "child-session")).thenReturn(state);
        when(child.streamEvents(anyList(), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        AgentScopeHarnessRuntime runtime = new AgentScopeHarnessRuntime(null, null);
        runtime.streamSubagent(
                parent,
                context,
                "再补充两个来源",
                ApprovalMode.EXPLORE
        ).collectList().block();

        assertEquals(1, state.getContext().size());
        Msg assignment = state.getContext().getFirst();
        assertEquals(MsgRole.SYSTEM, assignment.getRole());
        assertEquals("parent_assignment", assignment.getName());
        assertEquals("收集并整理官方资料", assignment.getTextContent());
        verify(child).saveAgentState("42", "child-session");

        ArgumentCaptor<PermissionContextState> permissionCaptor =
                ArgumentCaptor.forClass(PermissionContextState.class);
        var order = inOrder(child);
        order.verify(child).replacePermissionContext(
                eq("42"), eq("child-session"), permissionCaptor.capture());
        order.verify(child).streamEvents(anyList(),
                org.mockito.ArgumentMatchers.any(RuntimeContext.class));
        assertEquals(PermissionMode.EXPLORE, permissionCaptor.getValue().getMode());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Msg>> messageCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<RuntimeContext> childContextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).streamEvents(messageCaptor.capture(), childContextCaptor.capture());
        assertEquals(MsgRole.USER, messageCaptor.getValue().getFirst().getRole());
        assertEquals("再补充两个来源", messageCaptor.getValue().getFirst().getTextContent());
        assertEquals("42", childContextCaptor.getValue().getUserId());
        assertEquals("child-session", childContextCaptor.getValue().getSessionId());
        assertEquals(
                "execution-follow-up",
                HarnessSubagentLifecycleMiddleware.executionIdFrom(childContextCaptor.getValue())
        );

        ArgumentCaptor<RuntimeContext> parentContextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(manager).createAgentIfPresent(eq("research-agent"), parentContextCaptor.capture());
        for (RuntimeContext parentContext : parentContextCaptor.getAllValues()) {
            assertEquals("42", parentContext.getUserId());
            assertEquals("parent-session", parentContext.getSessionId());
        }
    }

    @Test
    void shouldRepairAStaleAssignmentBeforeReenteringTheChildSession() {
        HarnessAgent parent = mock(HarnessAgent.class);
        DefaultAgentManager manager = mock(DefaultAgentManager.class);
        ReActAgent child = mock(ReActAgent.class);
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("child-session")
                .context(List.of(
                        ParentAssignmentSystemPromptMiddleware.assignmentMessage("错误的 Agent 标签")
                ))
                .build();
        HarnessSubagentContext context = new HarnessSubagentContext(
                "research-agent",
                "42",
                "parent-session",
                "child-session",
                "核对三个官方来源并写出结论",
                "execution-repair"
        );

        when(parent.getSubagentAgentManager()).thenReturn(manager);
        when(manager.createAgentIfPresent(eq("research-agent"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(child));
        when(child.getAgentState("42", "child-session")).thenReturn(state);

        when(child.streamEvents(anyList(), org.mockito.ArgumentMatchers.any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        new AgentScopeHarnessRuntime(null, null).streamSubagent(parent, context, "继续核对")
                .collectList().block();

        assertEquals("核对三个官方来源并写出结论", state.getContext().getFirst().getTextContent());
        verify(child).saveAgentState("42", "child-session");
    }

}

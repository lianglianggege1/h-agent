package com.h.backend.chat.domain.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParentAssignmentSystemPromptMiddlewareTest {

    @Test
    void shouldNeverTurnAParentUserMessageIntoADelegation() {
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("parent-session")
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("42")
                .sessionId("parent-session")
                .agentState(state)
                .build();
        ParentAssignmentSystemPromptMiddleware.stageCurrentInput(context, "父会话用户问题");

        String prompt = new ParentAssignmentSystemPromptMiddleware()
                .onSystemPrompt(null, context, "base system")
                .block();

        assertEquals("base system", prompt);
        assertTrue(state.getContext().isEmpty());
    }

    @Test
    void shouldReplaceStaleAssignmentAndRemoveDuplicateCopies() {
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("child-session")
                .context(List.of(
                        ParentAssignmentSystemPromptMiddleware.assignmentMessage("旧的短标签"),
                        Msg.builder().role(MsgRole.USER).textContent("继续").build(),
                        ParentAssignmentSystemPromptMiddleware.assignmentMessage("另一个错误副本")
                ))
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("42")
                .sessionId("child-session")
                .agentState(state)
                .build();

        assertTrue(ParentAssignmentSystemPromptMiddleware.upsertAssignment(
                context, "  当前 child session 的完整委托  "
        ));
        assertEquals(2, state.getContext().size());
        assertEquals(
                "当前 child session 的完整委托",
                state.getContext().stream()
                        .filter(ParentAssignmentSystemPromptMiddleware::isAssignment)
                        .findFirst()
                        .orElseThrow()
                        .getTextContent()
        );
        assertFalse(ParentAssignmentSystemPromptMiddleware.upsertAssignment(
                context, "当前 child session 的完整委托"
        ));
    }

    @Test
    void shouldMergeAssignmentIntoProviderSystemPromptWithoutSendingASecondSystemMessage() {
        Msg assignment = Msg.builder()
                .name(ParentAssignmentSystemPromptMiddleware.MESSAGE_NAME)
                .role(MsgRole.SYSTEM)
                .textContent("核对所有官方来源")
                .build();
        Msg user = Msg.builder().role(MsgRole.USER).textContent("继续").build();
        AgentState state = AgentState.builder()
                .userId("42")
                .sessionId("child-session")
                .context(List.of(assignment, user))
                .build();
        RuntimeContext context = RuntimeContext.builder()
                .userId("42")
                .sessionId("child-session")
                .build();
        context.setAgentState(state);
        ParentAssignmentSystemPromptMiddleware middleware = new ParentAssignmentSystemPromptMiddleware();

        String prompt = middleware.onSystemPrompt(null, context, "base system").block();
        assertTrue(prompt.contains("base system"));
        assertTrue(prompt.contains("核对所有官方来源"));

        AtomicReference<ReasoningInput> forwarded = new AtomicReference<>();
        middleware.onReasoning(
                null,
                context,
                new ReasoningInput(List.of(assignment, user), List.of(), null),
                input -> {
                    forwarded.set(input);
                    return Flux.empty();
                }
        ).collectList().block();

        assertEquals(List.of(user), forwarded.get().messages());
        assertFalse(state.getContext().isEmpty());
        assertEquals(assignment, state.getContext().getFirst());
    }
}

package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.HarnessSubagentFailureReason;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HarnessSubagentLifecycleMiddlewareTest {

    @Test
    void shouldReportAccumulatedReasoningWhenChildEndsWithoutAResult() {
        AtomicReference<List<String>> failed = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, reasoning, content) -> { },
                (userId, sessionId, assignment, executionId, reasoning, reason, message) -> failed.set(
                        List.of(
                                userId, sessionId, assignment, executionId, reasoning,
                                reason.name(), message
                        )
                ),
                (userId, sessionId, event) -> { }
        );
        var context = RuntimeContext.builder().userId("73").sessionId("sub-failed").build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("创作一首宋词").build()
        ));
        Flux<io.agentscope.core.event.AgentEvent> reasoningEvents = middleware.onReasoning(
                null,
                context,
                new ReasoningInput(List.of(), List.of(), null),
                ignored -> Flux.just(
                        new ThinkingBlockDeltaEvent("reply-failed", "thinking-failed", "先确定词牌，"),
                        new ThinkingBlockDeltaEvent("reply-failed", "thinking-failed", "再安排上下阕。")
                )
        );

        middleware.onAgent(
                        null,
                        context,
                        input,
                        ignored -> Flux.concat(
                                Flux.just(new AgentStartEvent("sub-failed", "reply-failed", "child")),
                                reasoningEvents,
                                Flux.just(new AgentEndEvent("reply-failed"))
                        )
                )
                .collectList().block();

        assertEquals(
                List.of(
                        "73", "sub-failed", "创作一首宋词", "reply-failed",
                        "先确定词牌，再安排上下阕。",
                        HarnessSubagentFailureReason.PROTOCOL_INCOMPLETE.name(),
                        "AGENT_END arrived without a non-blank AGENT_RESULT"
                ),
                failed.get()
        );
    }

    @Test
    void shouldIncludeAccumulatedReasoningAtTheChildCompletionBoundary() {
        AtomicReference<List<String>> completed = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, executionId, reasoning, content) -> completed.set(
                        List.of(userId, sessionId, assignment, executionId, reasoning, content)
                ),
                (userId, sessionId, assignment, executionId, reasoning, reason, message) -> { },
                (userId, sessionId, event) -> { }
        );
        var context = RuntimeContext.builder().userId("73").sessionId("sub-reasoning").build();
        HarnessSubagentLifecycleMiddleware.stageExecutionId(context, "execution-product");
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("分析夏日意象").build()
        ));
        Flux<io.agentscope.core.event.AgentEvent> reasoningEvents = middleware.onReasoning(
                null,
                context,
                new ReasoningInput(List.of(), List.of(), null),
                ignored -> Flux.just(
                        new ThinkingBlockDeltaEvent("reply-live", "thinking-live", "先看蝉鸣，"),
                        new ThinkingBlockDeltaEvent("reply-live", "thinking-live", "再看晚风。")
                )
        );
        var result = new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("蝉鸣落在黄昏里。")
                .build());

        middleware.onAgent(
                        null,
                        context,
                        input,
                        ignored -> Flux.concat(
                                Flux.just(new AgentStartEvent("sub-reasoning", "reply-live", "child")),
                                reasoningEvents,
                                Flux.just(result)
                        )
                )
                .collectList().block();

        assertEquals(
                List.of(
                        "73", "sub-reasoning", "分析夏日意象", "execution-product",
                        "先看蝉鸣，再看晚风。", "蝉鸣落在黄昏里。"
                ),
                completed.get()
        );
    }

    @Test
    void shouldProjectResultFromTheChildOwnCompletionBoundary() {
        AtomicReference<List<String>> completed = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment, content) -> completed.set(
                        List.of(userId, sessionId, assignment, content)
                )
        );
        var context = RuntimeContext.builder().userId("73").sessionId("child-session").build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("写一篇夏日散文").build()
        ));
        var result = new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("蝉鸣落在黄昏里。")
                .build());

        middleware.onAgent(null, context, input, ignored -> Flux.just(result))
                .collectList().block();

        assertEquals(
                List.of("73", "child-session", "写一篇夏日散文", "蝉鸣落在黄昏里。"),
                completed.get()
        );
    }

    @Test
    void shouldProjectAssignmentBeforeTheChildExecutionStarts() {
        AtomicReference<String> startedAssignment = new AtomicReference<>();
        AtomicReference<String> assignmentVisibleInsideExecution = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> startedAssignment.set(assignment),
                (userId, sessionId, assignment, content) -> { }
        );
        var context = RuntimeContext.builder().userId("73").sessionId("child-session").build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("运行中就应展示的完整委托").build()
        ));

        middleware.onAgent(null, context, input, ignored -> {
                    assignmentVisibleInsideExecution.set(startedAssignment.get());
                    return Flux.empty();
                })
                .collectList().block();

        assertEquals("运行中就应展示的完整委托", assignmentVisibleInsideExecution.get());
    }

    @Test
    void shouldRepairTheCallScopedAssignmentBeforeChildExecutionStarts() {
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, content) -> { }
        );
        AgentState state = AgentState.builder()
                .userId("73")
                .sessionId("sub-child-session")
                .context(List.of(
                        ParentAssignmentSystemPromptMiddleware.assignmentMessage("并发暴露时串入的标签")
                ))
                .build();
        var context = RuntimeContext.builder()
                .userId("73")
                .sessionId("sub-child-session")
                .build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("当前子会话的完整委托").build()
        ));

        middleware.onAgent(null, context, input, ignored -> {
                    context.setAgentState(state);
                    return new ParentAssignmentSystemPromptMiddleware()
                            .onSystemPrompt(null, context, "基础提示词")
                            .thenMany(Flux.empty());
                })
                .collectList().block();

        assertEquals(
                "当前子会话的完整委托",
                state.getContext().getFirst().getTextContent()
        );
    }

    @Test
    void shouldNotOverwriteParentAssignmentWithAFollowUpUserMessage() {
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, content) -> { }
        );
        AgentState state = AgentState.builder()
                .userId("73")
                .sessionId("sub-child-session")
                .context(List.of(
                        ParentAssignmentSystemPromptMiddleware.assignmentMessage("父 Agent 的原始委托"),
                        Msg.builder().role(MsgRole.USER).textContent("首轮任务").build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("首轮回答").build()
                ))
                .build();
        var context = RuntimeContext.builder()
                .userId("73")
                .sessionId("sub-child-session")
                .build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("后续追加要求").build()
        ));

        middleware.onAgent(null, context, input, ignored -> {
                    context.setAgentState(state);
                    return new ParentAssignmentSystemPromptMiddleware()
                            .onSystemPrompt(null, context, "基础提示词")
                            .thenMany(Flux.empty());
                })
                .collectList().block();

        assertEquals("父 Agent 的原始委托", state.getContext().getFirst().getTextContent());
    }

    @Test
    void shouldPublishEveryChildEventWithoutWaitingForCompletion() {
        AtomicReference<String> observed = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, content) -> { },
                (userId, sessionId, event) -> observed.set(
                        userId + ":" + sessionId + ":" + ((TextBlockDeltaEvent) event).getDelta()
                )
        );
        var context = RuntimeContext.builder().userId("73").sessionId("sub-live").build();
        middleware.onReasoning(
                        null,
                        context,
                        new ReasoningInput(List.of(), List.of(), null),
                        ignored -> Flux.just(
                        new TextBlockDeltaEvent("reply-live", "block-live", "第一段")
                ))
                .collectList().block();

        assertEquals("73:sub-live:第一段", observed.get());
    }

    @Test
    void shouldPublishToolExecutionEventsFromTheActingBoundary() {
        AtomicReference<String> observed = new AtomicReference<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, content) -> { },
                (userId, sessionId, event) -> observed.set(
                        userId + ":" + sessionId + ":"
                                + ((ToolResultTextDeltaEvent) event).getDelta()
                )
        );
        var context = RuntimeContext.builder().userId("73").sessionId("sub-tool-live").build();

        middleware.onActing(
                        null,
                        context,
                        new ActingInput(List.of()),
                        ignored -> Flux.just(new ToolResultTextDeltaEvent(
                                "reply-live", "tool-live", "agent_spawn", "工具输出"
                        )))
                .collectList().block();

        assertEquals("73:sub-tool-live:工具输出", observed.get());
    }

    @Test
    void shouldNotPublishAReasoningEventAgainAtTheOuterAgentBoundary() {
        List<String> observedTypes = new ArrayList<>();
        var middleware = new HarnessSubagentLifecycleMiddleware(
                (userId, sessionId, assignment) -> { },
                (userId, sessionId, assignment, content) -> { },
                (userId, sessionId, event) -> observedTypes.add(event.getType().name())
        );
        var context = RuntimeContext.builder().userId("73").sessionId("sub-no-duplicate").build();
        var input = new AgentInput(List.of(
                Msg.builder().role(MsgRole.USER).textContent("实时委托").build()
        ));
        Flux<io.agentscope.core.event.AgentEvent> reasoningEvents = middleware.onReasoning(
                null,
                context,
                new ReasoningInput(List.of(), List.of(), null),
                ignored -> Flux.just(new TextBlockDeltaEvent(
                        "reply-live", "block-live", "只发布一次"
                ))
        );

        middleware.onAgent(null, context, input, ignored -> reasoningEvents)
                .collectList().block();

        assertEquals(List.of("TEXT_BLOCK_DELTA"), observedTypes);
    }
}

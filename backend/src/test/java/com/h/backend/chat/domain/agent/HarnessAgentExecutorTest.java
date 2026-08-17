package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessSubagentCompletion;
import com.h.backend.chat.application.HarnessSubagentExposure;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.interfaces.dto.HarnessSubagentStatus;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessAgentEventPayload;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessAgentExecutorTest {

    @Test
    void shouldCompleteClientStreamAtParentAgentEndWithoutWaitingForPostProcessing() throws Exception {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new HarnessEventMapper()
        );
        AgentDefinition definition = definition(harnessAgent);
        Msg finalReply = finalReply();
        ChatSessionMessageDto persisted = persistedReply();
        stubPersistence(chatSessionService, persisted);

        Sinks.Empty<Void> postProcessing = Sinks.empty();
        CountDownLatch harnessCompleted = new CountDownLatch(1);
        AtomicBoolean postProcessingCancelled = new AtomicBoolean();
        Flux<AgentEvent> harnessEvents = Flux.concat(
                        Flux.just(
                                new AgentResultEvent(finalReply),
                                new AgentEndEvent("reply-parent")
                        ),
                        postProcessing.asMono().thenMany(Flux.empty())
                )
                .doOnCancel(() -> postProcessingCancelled.set(true))
                .doOnComplete(harnessCompleted::countDown);
        when(harnessAgent.streamEvents(eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(harnessEvents);
        AtomicInteger terminalCount = new AtomicInteger();

        List<ChatStreamEvent> events = clientEvents(executor, definition, terminalCount)
                .takeUntil(event -> "done".equals(event.type()))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals("done", events.getLast().type());
        assertFalse(harnessCompleted.await(100, TimeUnit.MILLISECONDS));
        assertFalse(postProcessingCancelled.get());
        assertEquals(1, terminalCount.get());

        postProcessing.tryEmitEmpty();
        assertTrue(harnessCompleted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void shouldIgnoreChildAgentEndWhenCompletingParentStream() {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new HarnessEventMapper()
        );
        AgentDefinition definition = definition(harnessAgent);
        Msg finalReply = finalReply();
        ChatSessionMessageDto persisted = persistedReply();
        stubPersistence(chatSessionService, persisted);

        AgentEndEvent childEnd = new AgentEndEvent("reply-child");
        childEnd.withSource("session-1/general-purpose");
        when(harnessAgent.streamEvents(eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        childEnd,
                        new AgentResultEvent(finalReply),
                        new AgentEndEvent("reply-parent")
                ));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();

        assertEquals(List.of(
                "harness_event",
                "harness_event",
                "harness_event",
                "chunk",
                "done"
        ), events.stream().map(ChatStreamEvent::type).toList());
        assertEquals(persisted, events.getLast().message());
    }

    @Test
    void shouldStillCancelHarnessWhenClientDisconnectsBeforeParentEnd() throws Exception {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new HarnessEventMapper()
        );
        AgentDefinition definition = definition(harnessAgent);
        CountDownLatch harnessCancelled = new CountDownLatch(1);
        when(harnessAgent.streamEvents(eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.<AgentEvent>never().doOnCancel(harnessCancelled::countDown));
        AtomicInteger terminalCount = new AtomicInteger();

        Disposable client = clientEvents(executor, definition, terminalCount).subscribe();
        client.dispose();

        assertTrue(harnessCancelled.await(1, TimeUnit.SECONDS));
        assertEquals(1, terminalCount.get());
        verify(agentRunService).failRun(55L, "客户端已断开");
        verify(telemetryService).markFailure(any(), any());
    }

    @Test
    void shouldStreamAndPersistOnlyParentTextAndReasoning() {
        HarnessAgent harnessAgent = mock(HarnessAgent.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new HarnessEventMapper()
        );
        AgentDefinition definition = new AgentDefinition(
                "harness-agent",
                "协作 Agent",
                "协作",
                List.of("规划", "协作"),
                "父 Agent",
                harnessAgent,
                AgentRuntimeType.HARNESS_STREAMING,
                true
        );

        ThinkingBlockDeltaEvent parentThinking = new ThinkingBlockDeltaEvent("reply-parent", "think-1", "parent think");
        ThinkingBlockDeltaEvent childThinking = new ThinkingBlockDeltaEvent("reply-child", "think-2", "child think");
        childThinking.withSource("session-1/general-purpose");
        TextBlockDeltaEvent parentDelta = new TextBlockDeltaEvent("reply-parent", "block-1", "parent live");
        TextBlockDeltaEvent childDelta = new TextBlockDeltaEvent("reply-child", "block-2", "child private");
        childDelta.withSource("session-1/general-purpose");
        Msg finalReply = Msg.builder()
                .id("result-1")
                .name("harness-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("parent final")
                .build();
        when(harnessAgent.streamEvents(eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        parentThinking,
                        childThinking,
                        parentDelta,
                        childDelta,
                        new AgentResultEvent(finalReply)
                ));

        ChatSessionMessageDto persisted = new ChatSessionMessageDto(
                "202",
                "assistant",
                "TEXT",
                "parent final",
                null,
                List.of(),
                LocalDateTime.of(2026, 8, 10, 10, 0)
        );
        when(chatSessionService.appendReasoningMessage(1L, "session-1", "parent think")).thenReturn(201L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "parent final")).thenReturn(202L);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 202L)).thenReturn(persisted);
        AtomicInteger terminalCount = new AtomicInteger();

        List<ChatStreamEvent> events = Flux.<ChatStreamEvent>create(sink -> executor.execute(
                        new ChatAgentExecutionCommand(
                                sink,
                                1L,
                                null,
                                "session-1",
                                "solve it",
                                null,
                                "unused-by-harness",
                                definition,
                                new AgentRunService.AgentRunHandle(55L),
                                new AgentRunTelemetryService.TelemetryRun(null, "trace-55"),
                                terminalCount::incrementAndGet
                        )
                ))
                .collectList()
                .block();

        assertEquals(List.of(
                "harness_event",
                "reasoning",
                "harness_event",
                "harness_event",
                "chunk",
                "harness_event",
                "harness_event",
                "done"
        ), events.stream().map(ChatStreamEvent::type).toList());
        assertEquals("parent think", events.get(1).content());
        assertEquals("parent live", events.get(4).content());
        assertEquals(0, events.stream().filter(event -> "child think".equals(event.content())).count());
        assertEquals(0, events.stream().filter(event -> "child private".equals(event.content())).count());
        assertEquals(persisted, events.get(7).message());
        assertEquals(1, terminalCount.get());

        ArgumentCaptor<RuntimeContext> contextCaptor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(harnessAgent).streamEvents(eq("solve it"), contextCaptor.capture());
        assertEquals("1", contextCaptor.getValue().getUserId());
        assertEquals("session-1", contextCaptor.getValue().getSessionId());
        var persistenceOrder = inOrder(chatSessionService);
        persistenceOrder.verify(chatSessionService).appendReasoningMessage(1L, "session-1", "parent think");
        persistenceOrder.verify(chatSessionService).appendAssistantMessage(1L, "session-1", "parent final");
        verify(agentRunService).completeRun(55L, 202L);
        verify(telemetryService).markSuccess(any());
    }

    @Test
    void shouldRouteTargetedTurnToSubagentRuntimeAndCommitChildResult() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new HarnessEventMapper(),
                runtime,
                collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        Msg result = Msg.builder()
                .id("reply-child-2")
                .name("research-child")
                .role(MsgRole.ASSISTANT)
                .textContent("补充了三条官方来源。")
                .build();
        when(runtime.streamSubagent(
                harnessBean,
                new HarnessSubagentContext(
                        "research-agent", "1", "session-1", "child-runtime-1", "资料收集",
                        "execution-targeted"
                ),
                "补充官方来源"
        ))
                .thenReturn(Flux.just(new AgentResultEvent(result), new AgentEndEvent("reply-child-2")));
        HarnessSubagentSummaryDto completed = new HarnessSubagentSummaryDto(
                "child-runtime-1", "session-1",
                "资料收集", "补充官方来源", HarnessSubagentStatus.COMPLETED,
                0, LocalDateTime.now()
        );
        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-1", "execution-targeted",
                "补充了三条官方来源。"
        )).thenReturn(new HarnessSubagentCompletion(401L, completed));

        List<ChatStreamEvent> events = Flux.<ChatStreamEvent>create(sink -> executor.execute(
                        new ChatAgentExecutionCommand(
                                sink, 1L, null,
                                "child-runtime-1", "session-1", "research-child",
                                "research-agent", "session-1", "资料收集",
                                "execution-targeted",
                                "补充官方来源", null,
                                "unused-by-harness", definition,
                                new AgentRunService.AgentRunHandle(55L),
                                new AgentRunTelemetryService.TelemetryRun(null, "trace-55"),
                                () -> { }
                        )
                ))
                .collectList()
                .block();

        assertEquals("done", events.getLast().type());
        verify(runtime).streamSubagent(
                harnessBean,
                new HarnessSubagentContext(
                        "research-agent", "1", "session-1", "child-runtime-1", "资料收集",
                        "execution-targeted"
                ),
                "补充官方来源"
        );
        verify(runtime, org.mockito.Mockito.never()).streamParent(any(), any(), any());
        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-1", "execution-targeted",
                "补充了三条官方来源。"
        );
        verify(chatSessionService, org.mockito.Mockito.never()).appendAssistantMessage(any(), any(), any());
        verify(agentRunService).completeRun(55L, 401L);
    }

    @Test
    void shouldPersistExposedSubagentBeforePublishingParentCompletion() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        ChatSessionMessageDto persisted = persistedReply();
        stubPersistence(chatSessionService, persisted);
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new ToolCallDeltaEvent(
                                "reply-parent", "spawn-1", "agent_spawn",
                                "{\"agent_id\":\"research-agent\",\"task\":\"收集官方资料并给出来源\",\"label\":\"资料收集\"}"
                        ),
                        new SubagentExposedEvent(
                                "research-child", "research-agent", "child-runtime-1", "资料收集"
                        ),
                        new ToolCallEndEvent("reply-parent", "spawn-1", "agent_spawn"),
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        clientEvents(executor, definition, new AtomicInteger()).collectList().block();

        verify(collaborationService).exposeSubagent(
                1L,
                "session-1",
                new HarnessSubagentExposure(
                        "research-child",
                        "research-agent",
                        "session-1",
                        "child-runtime-1",
                        "资料收集",
                        "资料收集"
                )
        );
    }

    @Test
    void shouldNotGuessAssignmentsAcrossChildrenOfTheSameAgentType() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new ToolCallDeltaEvent(
                                "reply-parent", "spawn-1", "agent_spawn",
                                "{\"agent_id\":\"general-purpose\",\"task\":\"先核对后端\"}"
                        ),
                        new ToolCallEndEvent("reply-parent", "spawn-1", "agent_spawn"),
                        new ToolCallDeltaEvent(
                                "reply-parent", "spawn-2", "agent_spawn",
                                "{\"agent_id\":\"general-purpose\",\"task\":\"再核对前端\"}"
                        ),
                        new ToolCallEndEvent("reply-parent", "spawn-2", "agent_spawn"),
                        new SubagentExposedEvent(
                                "child-1", "general-purpose", "child-session-1", "后端核对"
                        ),
                        new SubagentExposedEvent(
                                "child-2", "general-purpose", "child-session-2", "前端核对"
                        ),
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        clientEvents(executor, definition, new AtomicInteger()).collectList().block();

        var order = inOrder(collaborationService);
        order.verify(collaborationService).exposeSubagent(
                1L, "session-1",
                new HarnessSubagentExposure(
                        "child-1", "general-purpose", "session-1", "child-session-1",
                        "后端核对", "后端核对"
                )
        );
        order.verify(collaborationService).exposeSubagent(
                1L, "session-1",
                new HarnessSubagentExposure(
                        "child-2", "general-purpose", "session-1", "child-session-2",
                        "前端核对", "前端核对"
                )
        );
    }

    @Test
    void shouldProjectSpawnedChildLifecycleIntoIndependentThread() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        ChatSessionMessageDto persisted = persistedReply();
        stubPersistence(chatSessionService, persisted);
        AgentStartEvent childStart = new AgentStartEvent(
                "child-runtime-2", "reply-child-3", "research-agent"
        );
        childStart.withSource("parent/research-agent");
        Msg childReply = Msg.builder()
                .id("reply-child-3")
                .name("research-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("子任务资料已完成。")
                .build();
        AgentResultEvent childResult = new AgentResultEvent(childReply);
        childResult.withSource("parent/research-agent");
        AgentEndEvent childEnd = new AgentEndEvent("reply-child-3");
        childEnd.withSource("parent/research-agent");
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new SubagentExposedEvent(
                                "research-child-2", "research-agent", "child-runtime-2", "资料收集"
                        ),
                        childStart,
                        childResult,
                        childEnd,
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));
        HarnessSubagentSummaryDto running = new HarnessSubagentSummaryDto(
                "child-runtime-2", "session-1",
                "资料收集", "资料收集", HarnessSubagentStatus.RUNNING,
                0, LocalDateTime.now()
        );
        when(collaborationService.markRunning(
                1L, "session-1", "child-runtime-2", "reply-child-3"
        )).thenReturn(running);
        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-2", "reply-child-3", "子任务资料已完成。"
        )).thenReturn(new HarnessSubagentCompletion(402L, new HarnessSubagentSummaryDto(
                "child-runtime-2", "session-1",
                "资料收集", "资料收集", HarnessSubagentStatus.COMPLETED,
                0, LocalDateTime.now()
        )));

        clientEvents(executor, definition, new AtomicInteger()).collectList().block();

        verify(collaborationService).markRunning(
                1L, "session-1", "child-runtime-2", "reply-child-3"
        );
        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-2", "reply-child-3", "子任务资料已完成。"
        );
    }

    @Test
    void shouldRelayRealChildDeltasWithoutDuplicatingTheParentEmitterCopy() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessSubagentEventRelay relay = new HarnessSubagentEventRelay();
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService, relay
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        HarnessSubagentSummaryDto exposed = new HarnessSubagentSummaryDto(
                "child-runtime-relay", "session-1", "实时协作者", "完整的父委托",
                HarnessSubagentStatus.AVAILABLE, 0, LocalDateTime.now()
        );
        HarnessSubagentSummaryDto running = new HarnessSubagentSummaryDto(
                "child-runtime-relay", "session-1", "实时协作者", "完整的父委托",
                HarnessSubagentStatus.RUNNING, 0, LocalDateTime.now()
        );
        when(collaborationService.exposeSubagent(eq(1L), eq("session-1"), any()))
                .thenReturn(exposed);
        when(collaborationService.markRunning(
                1L, "session-1", "child-runtime-relay", "reply-child-relay"
        )).thenReturn(running);

        TextBlockDeltaEvent childDelta = new TextBlockDeltaEvent(
                "reply-child-relay", "block-relay", "正在实时生成"
        );
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.create(sink -> {
                    sink.next(new SubagentExposedEvent(
                            "child-relay", "general-purpose",
                            "child-runtime-relay", "实时协作者"
                    ));
                    relay.publish("1", "child-runtime-relay",
                            new AgentStartEvent(null, "reply-child-relay", "general-purpose-subagent"));
                    relay.publish("1", "child-runtime-relay", childDelta);
                    // AgentScope call() 路径还会把同一原始事件送入父 emitter。
                    sink.next(childDelta);
                    sink.next(new AgentResultEvent(finalReply()));
                    sink.next(new AgentEndEvent("reply-parent"));
                    sink.complete();
                }));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();

        List<HarnessAgentEventPayload> payloads = events.stream()
                .filter(event -> "harness_event".equals(event.type()))
                .map(ChatStreamEvent::payload)
                .map(HarnessAgentEventPayload.class::cast)
                .toList();
        HarnessAgentEventPayload start = payloads.stream()
                .filter(payload -> "AGENT_START".equals(payload.eventType())
                        && "SUBAGENT".equals(payload.source().scope()))
                .findFirst()
                .orElseThrow();
        HarnessAgentEventPayload delta = payloads.stream()
                .filter(payload -> "TEXT_BLOCK_DELTA".equals(payload.eventType())
                        && "SUBAGENT".equals(payload.source().scope()))
                .findFirst()
                .orElseThrow();

        assertEquals("child-runtime-relay", start.data().get("agentSessionId"));
        assertEquals("完整的父委托", start.projection().subagent().assignment());
        assertEquals("child-runtime-relay", delta.data().get("agentSessionId"));
        assertEquals("正在实时生成", delta.data().get("delta"));
        assertEquals(1L, payloads.stream()
                .filter(payload -> childDelta.getId().equals(payload.eventId()))
                .count());
    }

    @Test
    void shouldPublishRunningAfterReplayingStartsThatPrecedeExposure() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessSubagentEventRelay relay = new HarnessSubagentEventRelay();
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService, relay
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        var exposedSessions = new java.util.HashSet<String>();
        for (int index = 0; index < 3; index++) {
            relay.publish(
                    "1",
                    "sub-race-" + index,
                    new AgentStartEvent(null, "reply-race-" + index, "general-purpose-subagent")
            );
        }
        when(collaborationService.exposeSubagent(eq(1L), eq("session-1"), any()))
                .thenAnswer(invocation -> {
                    HarnessSubagentExposure exposure = invocation.getArgument(2);
                    exposedSessions.add(exposure.sessionId());
                    int order = Integer.parseInt(exposure.sessionId().substring("sub-race-".length()));
                    return new HarnessSubagentSummaryDto(
                            exposure.sessionId(), "session-1", exposure.displayName(), exposure.assignment(),
                            HarnessSubagentStatus.AVAILABLE, order, LocalDateTime.now()
                    );
                });
        when(collaborationService.markRunning(eq(1L), eq("session-1"), any(), any()))
                .thenAnswer(invocation -> {
                    String sessionId = invocation.getArgument(2);
                    if (!exposedSessions.contains(sessionId)) {
                        return null;
                    }
                    int order = Integer.parseInt(sessionId.substring("sub-race-".length()));
                    return new HarnessSubagentSummaryDto(
                            sessionId, "session-1", "协作者-" + order, "任务-" + order,
                            HarnessSubagentStatus.RUNNING, order, LocalDateTime.now()
                    );
                });
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new SubagentExposedEvent("child-race-0", "general-purpose", "sub-race-0", "协作者-0"),
                        new SubagentExposedEvent("child-race-1", "general-purpose", "sub-race-1", "协作者-1"),
                        new SubagentExposedEvent("child-race-2", "general-purpose", "sub-race-2", "协作者-2"),
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();
        var latestStatuses = new java.util.HashMap<String, HarnessSubagentStatus>();
        events.stream()
                .filter(event -> event.payload() instanceof HarnessAgentEventPayload)
                .map(event -> (HarnessAgentEventPayload) event.payload())
                .filter(payload -> payload.projection() != null && payload.projection().subagent() != null)
                .forEach(payload -> latestStatuses.put(
                        payload.projection().subagent().sessionId(),
                        payload.projection().subagent().status()
                ));

        assertEquals(3, latestStatuses.size());
        assertTrue(latestStatuses.values().stream().allMatch(HarnessSubagentStatus.RUNNING::equals));
    }

    @Test
    void shouldCompleteSubagentOnlyAfterAgentEnd() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        AgentStartEvent childStart = new AgentStartEvent(
                "child-runtime-end", "reply-child-end", "research-agent"
        );
        childStart.withSource("parent/research-agent");
        AgentResultEvent childResult = new AgentResultEvent(Msg.builder()
                .id("reply-child-end")
                .name("research-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("子任务完成结果。")
                .build());
        childResult.withSource("parent/research-agent");
        AgentEndEvent childEnd = new AgentEndEvent("reply-child-end");
        childEnd.withSource("parent/research-agent");

        HarnessSubagentSummaryDto completed = new HarnessSubagentSummaryDto(
                "child-runtime-end", "session-1", "资料收集", "资料收集",
                HarnessSubagentStatus.COMPLETED, 0, LocalDateTime.now()
        );
        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-end", "reply-child-end", "子任务完成结果。"
        )).thenReturn(new HarnessSubagentCompletion(403L, completed));
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.concat(
                        Flux.just(
                                new SubagentExposedEvent(
                                        "research-child-end", "research-agent",
                                        "child-runtime-end", "资料收集"
                                ),
                                childStart,
                                childResult
                        ),
                        Flux.defer(() -> {
                            verify(collaborationService, org.mockito.Mockito.never()).completeSubagent(
                                    1L, "session-1", "child-runtime-end", "reply-child-end",
                                    "子任务完成结果。"
                            );
                            return Flux.just(
                                    childEnd,
                                    new AgentResultEvent(finalReply()),
                                    new AgentEndEvent("reply-parent")
                            );
                        })
                ));

        clientEvents(executor, definition, new AtomicInteger()).collectList().block();

        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-end", "reply-child-end", "子任务完成结果。"
        );
    }

    @Test
    void shouldIgnoreSpawnWrapperEndAfterChildResultWasCommitted() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        String source = "session-1/general-purpose";
        AgentStartEvent wrapperStart = new AgentStartEvent(
                "child-runtime-wrapper", null, "general-purpose"
        );
        wrapperStart.withSource(source);
        AgentStartEvent childStart = new AgentStartEvent(
                "child-runtime-wrapper", "reply-child-wrapper", "general-purpose-subagent"
        );
        childStart.withSource(source);
        AgentResultEvent childResult = new AgentResultEvent(Msg.builder()
                .id("reply-child-wrapper")
                .name("general-purpose-subagent")
                .role(MsgRole.ASSISTANT)
                .textContent("子任务已完成。")
                .build());
        childResult.withSource(source);
        AgentEndEvent childEnd = new AgentEndEvent("reply-child-wrapper");
        childEnd.withSource(source);
        AgentEndEvent wrapperEnd = new AgentEndEvent(null);
        wrapperEnd.withSource(source);

        HarnessSubagentSummaryDto completed = new HarnessSubagentSummaryDto(
                "child-runtime-wrapper", "session-1", "通用协作者", "完成子任务",
                HarnessSubagentStatus.COMPLETED, 0, LocalDateTime.now()
        );
        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-wrapper", "reply-child-wrapper", "子任务已完成。"
        )).thenReturn(new HarnessSubagentCompletion(404L, completed));
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new SubagentExposedEvent(
                                "general-child", "general-purpose",
                                "child-runtime-wrapper", "通用协作者"
                        ),
                        wrapperStart,
                        childStart,
                        childResult,
                        childEnd,
                        wrapperEnd,
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();

        assertEquals("done", events.getLast().type());
        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-wrapper", "reply-child-wrapper", "子任务已完成。"
        );
        verify(collaborationService, org.mockito.Mockito.never()).failSubagent(
                eq(1L), eq("session-1"), eq("child-runtime-wrapper"),
                any(), any(), any()
        );
    }

    @Test
    void shouldCorrelateConcurrentInvocationsOfSameAgentByReplyId() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        String sharedSource = "session-1/general-purpose";
        AgentStartEvent firstStart = new AgentStartEvent(
                "child-runtime-intp", "reply-intp", "general-purpose-subagent"
        );
        firstStart.withSource(sharedSource);
        AgentStartEvent secondStart = new AgentStartEvent(
                "child-runtime-intj", "reply-intj", "general-purpose-subagent"
        );
        secondStart.withSource(sharedSource);
        AgentResultEvent firstResult = new AgentResultEvent(Msg.builder()
                .id("reply-intp")
                .name("general-purpose-subagent")
                .role(MsgRole.ASSISTANT)
                .textContent("INTP 日志")
                .build());
        firstResult.withSource(sharedSource);
        AgentResultEvent secondResult = new AgentResultEvent(Msg.builder()
                .id("reply-intj")
                .name("general-purpose-subagent")
                .role(MsgRole.ASSISTANT)
                .textContent("INTJ 日志")
                .build());
        secondResult.withSource(sharedSource);
        AgentEndEvent firstEnd = new AgentEndEvent("reply-intp");
        firstEnd.withSource(sharedSource);
        AgentEndEvent secondEnd = new AgentEndEvent("reply-intj");
        secondEnd.withSource(sharedSource);

        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-intp", "reply-intp", "INTP 日志"
        )).thenReturn(new HarnessSubagentCompletion(405L, new HarnessSubagentSummaryDto(
                "child-runtime-intp", "session-1", "INTP 日志", "写 INTP 日志",
                HarnessSubagentStatus.COMPLETED, 0, LocalDateTime.now()
        )));
        when(collaborationService.completeSubagent(
                1L, "session-1", "child-runtime-intj", "reply-intj", "INTJ 日志"
        )).thenReturn(new HarnessSubagentCompletion(406L, new HarnessSubagentSummaryDto(
                "child-runtime-intj", "session-1", "INTJ 日志", "写 INTJ 日志",
                HarnessSubagentStatus.COMPLETED, 1, LocalDateTime.now()
        )));
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        firstStart,
                        secondStart,
                        firstResult,
                        secondResult,
                        firstEnd,
                        secondEnd,
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();

        assertEquals("done", events.getLast().type());
        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-intp", "reply-intp", "INTP 日志"
        );
        verify(collaborationService).completeSubagent(
                1L, "session-1", "child-runtime-intj", "reply-intj", "INTJ 日志"
        );
    }

    @Test
    void shouldKeepParentSuccessfulWhenOneSubagentProjectionFails() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        stubPersistence(chatSessionService, persistedReply());

        AgentStartEvent failedChildStart = new AgentStartEvent(
                "child-runtime-failed", "reply-failed", "general-purpose-subagent"
        );
        failedChildStart.withSource("session-1/general-purpose");
        AgentEndEvent failedChildEnd = new AgentEndEvent("reply-failed");
        failedChildEnd.withSource("session-1/general-purpose");
        when(collaborationService.failSubagent(
                1L, "session-1", "child-runtime-failed", "reply-failed",
                HarnessSubagentFailureReason.PROTOCOL_INCOMPLETE,
                "AGENT_END arrived without AGENT_RESULT"
        )).thenThrow(new IllegalStateException("projection storage unavailable"));
        when(runtime.streamParent(eq(harnessBean), eq("solve it"), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        failedChildStart,
                        failedChildEnd,
                        new AgentResultEvent(finalReply()),
                        new AgentEndEvent("reply-parent")
                ));

        List<ChatStreamEvent> events = clientEvents(executor, definition, new AtomicInteger())
                .collectList()
                .block();

        assertEquals("done", events.getLast().type());
        verify(agentRunService).completeRun(55L, 202L);
        verify(agentRunService, org.mockito.Mockito.never()).failRun(any(), any());
    }

    @Test
    void shouldMarkTargetedSubagentFailedWhenRuntimeErrors() {
        HarnessRuntime runtime = mock(HarnessRuntime.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        HarnessAgentExecutor executor = new HarnessAgentExecutor(
                chatSessionService, agentRunService, telemetryService,
                new HarnessEventMapper(), runtime, collaborationService
        );
        Object harnessBean = new Object();
        AgentDefinition definition = new AgentDefinition(
                "harness-agent", "协作 Agent", "协作", List.of("协作"),
                "父 Agent", harnessBean, AgentRuntimeType.HARNESS_STREAMING, true
        );
        when(runtime.streamSubagent(
                harnessBean,
                new HarnessSubagentContext(
                        "research-agent", "1", "session-1", "child-runtime-1", "资料收集",
                        "execution-error"
                ),
                "补充来源"
        ))
                .thenReturn(Flux.error(new IllegalStateException("child unavailable")));

        Flux.<ChatStreamEvent>create(sink -> executor.execute(new ChatAgentExecutionCommand(
                        sink, 1L, null,
                        "child-runtime-1", "session-1", "research-child",
                        "research-agent", "session-1", "资料收集",
                        "execution-error",
                        "补充来源", null,
                        "unused", definition,
                        new AgentRunService.AgentRunHandle(55L),
                        new AgentRunTelemetryService.TelemetryRun(null, "trace-55"),
                        () -> { }
                )))
                .collectList()
                .block();

        verify(collaborationService).failSubagent(
                1L, "session-1", "child-runtime-1", "execution-error",
                HarnessSubagentFailureReason.EXECUTION_ERROR, "child unavailable"
        );
    }

    private Flux<ChatStreamEvent> clientEvents(
            HarnessAgentExecutor executor,
            AgentDefinition definition,
            AtomicInteger terminalCount
    ) {
        return Flux.create(sink -> executor.execute(new ChatAgentExecutionCommand(
                sink,
                1L,
                null,
                "session-1",
                "solve it",
                null,
                "unused-by-harness",
                definition,
                new AgentRunService.AgentRunHandle(55L),
                new AgentRunTelemetryService.TelemetryRun(null, "trace-55"),
                terminalCount::incrementAndGet
        )));
    }

    private AgentDefinition definition(HarnessAgent harnessAgent) {
        return new AgentDefinition(
                "harness-agent",
                "协作 Agent",
                "协作",
                List.of("规划", "协作"),
                "父 Agent",
                harnessAgent,
                AgentRuntimeType.HARNESS_STREAMING,
                true
        );
    }

    private Msg finalReply() {
        return Msg.builder()
                .id("result-1")
                .name("harness-agent")
                .role(MsgRole.ASSISTANT)
                .textContent("parent final")
                .build();
    }

    private ChatSessionMessageDto persistedReply() {
        return new ChatSessionMessageDto(
                "202",
                "assistant",
                "TEXT",
                "parent final",
                null,
                List.of(),
                LocalDateTime.of(2026, 8, 10, 10, 0)
        );
    }

    private void stubPersistence(
            ChatSessionService chatSessionService,
            ChatSessionMessageDto persisted
    ) {
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "parent final"))
                .thenReturn(202L);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 202L)).thenReturn(persisted);
    }
}

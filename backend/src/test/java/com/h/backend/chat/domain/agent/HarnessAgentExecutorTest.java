package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessAgentExecutorTest {

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
}

package com.h.backend.chat.agent;

import com.h.backend.chat.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.dto.AgentStepPayloadDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatSessionService;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticSyncExecutorTest {

    @Test
    void shouldEmitAgentStepsFinalChunkAndDone() {
        CarRentalAssistant assistant = mock(CarRentalAssistant.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        AgentStepEventBridge bridge = new AgentStepEventBridge();
        AgenticSyncExecutor executor = new AgenticSyncExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                bridge
        );
        AgentDefinition agent = new AgentDefinition(
                "car-rental-assistant",
                "租车应急协助 Agent",
                "出行服务",
                List.of("应急"),
                "面向租车客户的拖车与紧急事件协助",
                assistant,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentRunService.AgentRunHandle runHandle = new AgentRunService.AgentRunHandle(77L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-agentic");

        when(assistant.chat("1:agent:car-rental-assistant:session-car", "need towing"))
                .thenAnswer(invocation -> {
                    bridge.emit(
                            "1:agent:car-rental-assistant:session-car",
                            new AgentStepPayloadDto(
                                    null,
                                    null,
                                    "invocation-1",
                                    "tow-truck-expert",
                                    "拖车专家",
                                    "AI_AGENT",
                                    "STARTED",
                                    1,
                                    3
                            )
                    );
                    return new ResultWithAgenticScope<>(mock(AgenticScope.class), "请先确认位置。");
                });
        when(chatSessionService.appendAssistantMessage(1L, "session-car", "请先确认位置。")).thenReturn(202L);

        List<ChatStreamEvent> events = Flux.<ChatStreamEvent>create(sink -> executor.execute(
                        new ChatAgentExecutionCommand(
                                sink,
                                1L,
                                null,
                                "session-car",
                                "need towing",
                                "1:agent:car-rental-assistant:session-car",
                                agent,
                                runHandle,
                                telemetryRun,
                                () -> {
                                },
                                () -> {
                                }
                        )
                ))
                .collectList()
                .block();

        assertEquals(3, events.size());
        assertEquals("agent_step", events.get(0).type());
        assertEquals("正在执行：拖车专家", events.get(0).content());
        AgentStepPayloadDto payload = (AgentStepPayloadDto) events.get(0).payload();
        assertEquals("77", payload.runId());
        assertEquals("car-rental-assistant", payload.agentId());
        assertEquals("chunk", events.get(1).type());
        assertEquals("请先确认位置。", events.get(1).content());
        assertEquals("done", events.get(2).type());
        verify(chatSessionService).appendAssistantMessage(1L, "session-car", "请先确认位置。");
        verify(agentRunService).completeRun(77L, 202L);
        verify(telemetryService).markSuccess(telemetryRun);
    }

    @Test
    void shouldInvokeSelectedAgentBeanFromCommand() {
        CarRentalAssistant selectedAssistant = mock(CarRentalAssistant.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        AgenticSyncExecutor executor = new AgenticSyncExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new AgentStepEventBridge()
        );
        AgentDefinition agent = new AgentDefinition(
                "car-rental-assistant",
                "租车应急协助 Agent",
                "出行服务",
                List.of("应急"),
                "面向租车客户的拖车与紧急事件协助",
                selectedAssistant,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentRunService.AgentRunHandle runHandle = new AgentRunService.AgentRunHandle(77L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-agentic");

        when(selectedAssistant.chat("1:agent:car-rental-assistant:session-car", "need towing"))
                .thenReturn(new ResultWithAgenticScope<>(mock(AgenticScope.class), "已联系拖车。"));
        when(chatSessionService.appendAssistantMessage(1L, "session-car", "已联系拖车。")).thenReturn(202L);

        Flux.<ChatStreamEvent>create(sink -> executor.execute(
                        new ChatAgentExecutionCommand(
                                sink,
                                1L,
                                null,
                                "session-car",
                                "need towing",
                                "1:agent:car-rental-assistant:session-car",
                                agent,
                                runHandle,
                                telemetryRun,
                                () -> {
                                },
                                () -> {
                                }
                        )
                ))
                .collectList()
                .block();

        verify(selectedAssistant).chat("1:agent:car-rental-assistant:session-car", "need towing");
    }

    @Test
    void shouldFailRunWhenAgenticReplyIsBlank() {
        CarRentalAssistant assistant = mock(CarRentalAssistant.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService telemetryService = mock(AgentRunTelemetryService.class);
        AgenticSyncExecutor executor = new AgenticSyncExecutor(
                chatSessionService,
                agentRunService,
                telemetryService,
                new AgentStepEventBridge()
        );
        AgentDefinition agent = new AgentDefinition(
                "car-rental-assistant",
                "租车应急协助 Agent",
                "出行服务",
                List.of("应急"),
                "面向租车客户的拖车与紧急事件协助",
                assistant,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentRunService.AgentRunHandle runHandle = new AgentRunService.AgentRunHandle(77L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-agentic");
        when(assistant.chat("1:agent:car-rental-assistant:session-car", "need towing"))
                .thenReturn(new ResultWithAgenticScope<>(mock(AgenticScope.class), "   "));

        List<ChatStreamEvent> events = Flux.<ChatStreamEvent>create(sink -> executor.execute(
                        new ChatAgentExecutionCommand(
                                sink,
                                1L,
                                null,
                                "session-car",
                                "need towing",
                                "1:agent:car-rental-assistant:session-car",
                                agent,
                                runHandle,
                                telemetryRun,
                                () -> {
                                },
                                () -> {
                                }
                        )
                ))
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 未返回有效内容")), events);
        verify(agentRunService).failRun(77L, "AI 未返回有效内容");
        verify(telemetryService).markFailure(
                org.mockito.Mockito.eq(telemetryRun),
                argThat(error -> error instanceof IllegalStateException
                        && "AI 未返回有效内容".equals(error.getMessage()))
        );
        verify(chatSessionService, never()).appendAssistantMessage(
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any()
        );
        verify(agentRunService, never()).completeRun(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }
}

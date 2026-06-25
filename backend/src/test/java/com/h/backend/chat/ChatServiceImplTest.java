package com.h.backend.chat;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.agent.AgentRuntimeType;
import com.h.backend.chat.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.agent.ChatAgentExecutor;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.chat.service.impl.ChatServiceImpl;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    @Test
    void shouldEmitErrorAndSkipAssistantWhenConcurrencyGuardRejects() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> new RejectedPermit("当前系统繁忙，请稍后再试")
        );

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-busy", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "当前系统繁忙，请稍后再试")), events);
        verify(hAssistant, never()).streamChat(any(), any());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(chatSessionService, never()).appendUserMessage(any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldEmitBusinessErrorWhenAgentIsUnknown() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, 2L, "missing-agent", "session-unknown", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "领域 Agent 不存在或未启用")), events);
        assertTrue(permit.released());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        verify(agentRunTelemetryService, never()).markFailure(any(), any());
    }

    @Test
    void shouldGenerateImageForSlashImageCommandWithoutCallingAssistant() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        AtomicInteger guardCalls = new AtomicInteger();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> {
                    guardCalls.incrementAndGet();
                    return new RecordingPermit();
                },
                imageGenerationService
        );
        ChatSessionMessageDto imageMessage = new ChatSessionMessageDto(
                "501",
                "assistant",
                "IMAGE",
                "一只白猫",
                null,
                List.of(new ChatMessageResourceDto(
                        "resource-1",
                        "IMAGE",
                        "/api/chat/resources/resource-1/content",
                        "/api/chat/resources/resource-1/download",
                        "generated-resource-1.png",
                        "image/png",
                        3L,
                        1024,
                        1024
                )),
                java.time.LocalDateTime.now()
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "/image 一只白猫")).thenReturn(101L);
        when(imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "COMMAND"
        ))).thenReturn(imageMessage);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "/image 一只白猫")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("image", "", imageMessage),
                new ChatStreamEvent("done", "")
        ), events);
        verify(chatSessionService).appendUserMessage(1L, "session-1", "/image 一只白猫");
        verify(imageGenerationService).generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "COMMAND"
        ));
        verify(hAssistant, never()).streamChat(any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        assertEquals(0, guardCalls.get());
    }

    @Test
    void shouldBypassChatConcurrencyGuardForSlashImageCommand() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        AtomicInteger guardCalls = new AtomicInteger();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> {
                    guardCalls.incrementAndGet();
                    return new RejectedPermit("当前系统繁忙，请稍后再试");
                },
                imageGenerationService
        );
        ChatSessionMessageDto imageMessage = new ChatSessionMessageDto(
                "501",
                "assistant",
                "IMAGE",
                "给我生成一张柴犬的图片",
                null,
                List.of(),
                java.time.LocalDateTime.now()
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "/image 给我生成一张柴犬的图片")).thenReturn(101L);
        when(imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "给我生成一张柴犬的图片",
                "COMMAND"
        ))).thenReturn(imageMessage);

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, 2L, null, "session-1", "/image 给我生成一张柴犬的图片")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("image", "", imageMessage),
                new ChatStreamEvent("done", "")
        ), events);
        assertEquals(0, guardCalls.get());
        verify(chatSessionService).assertActiveSession(1L, "session-1", 2L, "standard-chat");
        verify(imageGenerationService).generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "给我生成一张柴犬的图片",
                "COMMAND"
        ));
        verify(hAssistant, never()).streamChat(any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSubmitAgentWorkflowToExecutorAndReleasePermitWhenComplete() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("hello");
        RecordingDirectExecutorService executor = new RecordingDirectExecutorService();
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                executor,
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-submit", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-submit", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-submit");
        when(agentRunTelemetryService.startRun("session-submit", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-submit", 1L, 22L, 101L, "standard-chat", "trace-submit"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-submit", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-submit", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "hello"),
                new ChatStreamEvent("done", "")
        ), events);
        assertEquals(1, executor.submittedCount());
        assertTrue(permit.released());
    }

    @Test
    void shouldRenewPermitWhenStreamingChunksArrive() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("he").emitText("llo");
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-renew", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-renew", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-renew");
        when(agentRunTelemetryService.startRun("session-renew", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-renew", 1L, 22L, 101L, "standard-chat", "trace-renew"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-renew", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-renew", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "he"),
                new ChatStreamEvent("chunk", "llo"),
                new ChatStreamEvent("done", "")
        ), events);
        assertEquals(2, permit.renewCalls());
        assertTrue(permit.released());
    }

    @Test
    void shouldContinueFinalizingRunAfterSubscriberCancels() throws InterruptedException {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ControlledAsyncTokenStream tokenStream = new ControlledAsyncTokenStream("hello");
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-cancel", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-cancel", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-cancel");
        when(agentRunTelemetryService.startRun("session-cancel", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-cancel", 1L, 22L, 101L, "standard-chat", "trace-cancel"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-cancel", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());
        Disposable[] subscriptionRef = new Disposable[1];
        subscriptionRef[0] = chatService.streamChat(1L, 2L, null, "session-cancel", "hello")
                .subscribe(event -> {
                    receivedEvents.add(event);
                    if ("chunk".equals(event.type())) {
                        subscriptionRef[0].dispose();
                    }
                });

        assertTrue(tokenStream.awaitFirstChunk(1, TimeUnit.SECONDS));
        tokenStream.finishSuccessfully();

        verify(agentRunService, org.mockito.Mockito.timeout(1000)).completeRun(55L, 202L);
        verify(agentRunTelemetryService, org.mockito.Mockito.timeout(1000)).markSuccess(telemetryRun);
        verify(chatSessionService, org.mockito.Mockito.timeout(1000))
                .appendAssistantMessage(1L, "session-cancel", "hello");
        assertTrue(tokenStream.awaitCompletion(1, TimeUnit.SECONDS));
        assertTrue(permit.awaitReleased(1, TimeUnit.SECONDS));
        assertEquals(List.of(new ChatStreamEvent("chunk", "hello")), receivedEvents);
    }

    @Test
    void shouldSkipDoneEmissionWhenSinkAlreadyCancelledButStillFinalizeRun() throws Exception {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("hello");
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-cancelled-sink", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-cancelled-sink", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-cancelled-sink");
        when(agentRunTelemetryService.startRun("session-cancelled-sink", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-cancelled-sink", 1L, 22L, 101L, "standard-chat", "trace-cancelled-sink"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-cancelled-sink", "hello")).thenReturn(tokenStream);

        @SuppressWarnings("unchecked")
        FluxSink<ChatStreamEvent> sink = mock(FluxSink.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        doAnswer(invocation -> cancelled.get()).when(sink).isCancelled();
        doAnswer(invocation -> {
            ChatStreamEvent event = invocation.getArgument(0);
            if ("chunk".equals(event.type())) {
                cancelled.set(true);
                return sink;
            }
            throw new RuntimeException("done should not be emitted after cancellation");
        }).when(sink).next(any(ChatStreamEvent.class));
        doNothing().when(sink).complete();

        invokeRunChatStream(chatService, sink, permit, 1L, 2L, "session-cancelled-sink", "hello");

        verify(chatSessionService).appendAssistantMessage(1L, "session-cancelled-sink", "hello");
        verify(agentRunService).completeRun(55L, 202L);
        verify(agentRunService, never()).failRun(any(), any());
        verify(agentRunTelemetryService).markSuccess(telemetryRun);
        assertTrue(permit.released());
    }

    @Test
    void shouldNotRunAgentSetupOnSubscriptionThreadBeforeExecutorRuns() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RecordingExecutorService executor = new RecordingExecutorService();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                executor,
                (sessionId, userId) -> new RecordingPermit()
        );

        chatService.streamChat(1L, 2L, null, "session-async", "hello").subscribe();

        assertEquals(1, executor.submittedCount());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(systemPromptService, never()).resolvePromptId(any(), any());
        verify(chatSessionService, never()).appendUserMessage(any(), any(), any());
        verify(agentRunTelemetryService, never()).startRun(any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        verify(hAssistant, never()).streamChat(any(), any());
    }

    @Test
    void shouldReleasePermitWhenExecutorRejectsAgentWorkflow() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new RejectingExecutorService(),
                (sessionId, userId) -> permit
        );

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-rejected", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 服务调用失败")), events);
        assertTrue(permit.released());
        verify(hAssistant, never()).streamChat(any(), any());
    }

    @Test
    void shouldRouteCarRentalAgentToAgenticSyncExecutorWithoutResolvingPrompt() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RecordingChatAgentExecutor agenticExecutor = new RecordingChatAgentExecutor(AgentRuntimeType.AGENTIC_SYNC);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit(),
                null,
                new ChatStreamEventBridge(),
                List.of(agenticExecutor)
        );

        when(chatSessionService.appendUserMessage(1L, "session-car", "need towing")).thenReturn(101L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-car");
        when(agentRunTelemetryService.startRun("session-car", 1L, null)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-car", 1L, null, 101L, "car-rental-assistant", "trace-car"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, null, "car-rental-assistant", "session-car", "need towing")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "agentic-ok"),
                new ChatStreamEvent("done", "")
        ), events);
        verify(chatSessionService).assertActiveSession(1L, "session-car", null, "car-rental-assistant");
        verify(systemPromptService, never()).resolvePromptId(any(), any());
        verify(chatSessionService).appendUserMessage(1L, "session-car", "need towing");
        verify(agentRunService).createRun("session-car", 1L, null, 101L, "car-rental-assistant", "trace-car");
        verifyNoInteractions(hAssistant);
        assertEquals(
                "exec:v2:user:1:session:session-car:agent:car-rental-assistant",
                agenticExecutor.command.memoryId()
        );
        assertEquals("car-rental-assistant", agenticExecutor.command.agent().agentId());
    }

    @Test
    void shouldEmitReasoningEventsAndPersistReasoningBeforeAssistantReply() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitThinking("先明确目标。")
                .emitThinking("再列实现步骤。")
                .emitText("最终")
                .emitText("答案");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "hello")).thenReturn(101L);
        when(chatSessionService.appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。")).thenReturn(201L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "最终答案")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-reasoning");
        when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", "trace-reasoning"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("reasoning", "先明确目标。"),
                new ChatStreamEvent("reasoning", "再列实现步骤。"),
                new ChatStreamEvent("chunk", "最终"),
                new ChatStreamEvent("chunk", "答案"),
                new ChatStreamEvent("done", "")
        ), events);
        var inOrder = inOrder(chatSessionService);
        inOrder.verify(chatSessionService).appendUserMessage(1L, "session-1", "hello");
        inOrder.verify(chatSessionService).appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。");
        inOrder.verify(chatSessionService).appendAssistantMessage(1L, "session-1", "最终答案");
        verify(agentRunService).completeRun(55L, 202L);
        verify(agentRunTelemetryService).markSuccess(telemetryRun);
    }

    @Test
    void shouldNotPersistReasoningWhenRuntimeErrorOccursAfterThinking() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RuntimeException runtimeException = new RuntimeException("boom");
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitThinking("先分析")
                .emitErrorAfterThinking(runtimeException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-2", "hello")).thenReturn(111L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-error");
        when(agentRunTelemetryService.startRun("session-2", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "standard-chat", "trace-error"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-2", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-2", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("reasoning", "先分析"),
                new ChatStreamEvent("error", "AI 服务调用失败")
        ), events);
        verify(chatSessionService, never()).appendReasoningMessage(any(), any(), any());
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitChunkEventsAndDoneEventForSuccessfulStream() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("he").emitText("llo");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-1");
        when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", "trace-1"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "he"),
                new ChatStreamEvent("chunk", "llo"),
                new ChatStreamEvent("done", "")
        ), events);
        verify(chatSessionService).appendUserMessage(1L, "session-1", "hello");
        verify(agentRunTelemetryService).startRun("session-1", 1L, 22L);
        verify(agentRunService).createRun("session-1", 1L, 22L, 101L, "standard-chat", "trace-1");
        verify(chatSessionService).appendAssistantMessage(1L, "session-1", "hello");
        verify(agentRunService).completeRun(55L, 202L);
        verify(agentRunTelemetryService).markSuccess(telemetryRun);
    }

    @Test
    void shouldRecordToolUsageDuringStreamChat() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitTool("search_web")
                .emitText("hello");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "hello")).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "hello")).thenReturn(202L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-1");
        when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", "trace-1"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "hello"),
                new ChatStreamEvent("done", "")
        ), events);
        verify(agentRunService).recordToolUsage(55L, "search_web");
        verify(agentRunService).completeRun(55L, 202L);
    }

    @Test
    void shouldEmitErrorEventWhenModelMissing() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(new ModelDisabledException("disabled"));
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-1", "hello")).thenReturn(101L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-2");
        when(agentRunTelemetryService.startRun("session-1", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", "trace-2"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY")), events);
        verify(agentRunService).failRun(55L, "AI 服务未配置 OPENAI_API_KEY");
        verify(agentRunTelemetryService).markFailure(telemetryRun, tokenStream.error);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldNotExecuteSideEffectsBeforeSubscription() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        chatService.streamChat(1L, 2L, null, "session-lazy", "hello");

        verify(chatSessionService, never()).appendUserMessage(any(), any(), any());
        verify(agentRunTelemetryService, never()).startRun(any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldEmitErrorEventWhenStreamCompletesWithoutText() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-empty", "hello")).thenReturn(121L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-empty");
        when(agentRunTelemetryService.startRun("session-empty", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-empty", 1L, 22L, 121L, "standard-chat", "trace-empty"))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat("1:22:session-empty", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-empty", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 未返回有效内容")), events);
        verify(agentRunService).failRun(88L, "AI 未返回有效内容");
        verify(agentRunTelemetryService).markFailure(
                org.mockito.Mockito.eq(telemetryRun),
                argThat(error -> error instanceof IllegalStateException
                        && "AI 未返回有效内容".equals(error.getMessage()))
        );
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldCompleteRunWhenImageToolPublishesImageWithoutAssistantText() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        ChatStreamEventBridge chatStreamEventBridge = new ChatStreamEventBridge();
        ChatSessionMessageDto imageMessage = new ChatSessionMessageDto(
                "501",
                "assistant",
                "IMAGE",
                "一只白猫",
                null,
                List.of(),
                java.time.LocalDateTime.now()
        );
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitImageMessage(() -> chatStreamEventBridge.publishImage("1:22:session-image-tool", imageMessage))
                .emitTool("generateImage");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamEventBridge
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-image-tool", "画一只白猫")).thenReturn(121L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-image-tool");
        when(agentRunTelemetryService.startRun("session-image-tool", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-image-tool", 1L, 22L, 121L, "standard-chat", "trace-image-tool"))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat("1:22:session-image-tool", "画一只白猫")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-image-tool", "画一只白猫")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("image", "", imageMessage),
                new ChatStreamEvent("done", "")
        ), events);
        verify(agentRunService).recordToolUsage(88L, "generateImage");
        verify(agentRunService).completeRun(88L, null);
        verify(agentRunTelemetryService).markSuccess(telemetryRun);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
        verify(agentRunService, never()).failRun(any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenGuardrailMessageIsBlank() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        InputGuardrailException guardrailException = new InputGuardrailException("   ");
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-blank", "hello")).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-blank", "平台检测到您的消息不符合使用规范，已自动拦截。"))
                .thenReturn(303L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-blank");
        when(agentRunTelemetryService.startRun("session-blank", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-blank", 1L, 22L, 111L, "standard-chat", "trace-blank"))
                .thenReturn(new AgentRunService.AgentRunHandle(77L));
        when(hAssistant.streamChat("1:22:session-blank", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-blank", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")), events);
        verify(chatSessionService).appendBlockedMessage(1L, "session-blank", "平台检测到您的消息不符合使用规范，已自动拦截。");
        verify(agentRunService).failRun(77L, "平台检测到您的消息不符合使用规范，已自动拦截。");
        verify(agentRunTelemetryService).markFailure(telemetryRun, guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenGuardrailFails() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-guardrail", "杀人")).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-guardrail");
        when(agentRunTelemetryService.startRun("session-guardrail", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-guardrail", 1L, 22L, 111L, "standard-chat", "trace-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-guardrail", "杀人")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-guardrail", "杀人")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")), events);
        verify(chatSessionService).appendBlockedMessage(1L, "session-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(agentRunTelemetryService).markFailure(telemetryRun, guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenCreatingStreamFailsGuardrail() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-create-guardrail", "杀人")).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-create-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-create-guardrail");
        when(agentRunTelemetryService.startRun("session-create-guardrail", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-create-guardrail", 1L, 22L, 111L, "standard-chat", "trace-create-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-create-guardrail", "杀人")).thenThrow(guardrailException);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-create-guardrail", "杀人")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")), events);
        verify(chatSessionService).appendBlockedMessage(1L, "session-create-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(agentRunTelemetryService).markFailure(telemetryRun, guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenStartingStreamFailsGuardrail() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        FakeTokenStream tokenStream = new FakeTokenStream().emitStartError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-start-guardrail", "杀人")).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-start-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-start-guardrail");
        when(agentRunTelemetryService.startRun("session-start-guardrail", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-start-guardrail", 1L, 22L, 111L, "standard-chat", "trace-start-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-start-guardrail", "杀人")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-start-guardrail", "杀人")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")), events);
        verify(chatSessionService).appendBlockedMessage(1L, "session-start-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(agentRunTelemetryService).markFailure(telemetryRun, guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitErrorEventWhenRuntimeErrorOccurs() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RuntimeException runtimeException = new RuntimeException("boom");
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(runtimeException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(1L, "session-2", "hello")).thenReturn(111L);
        AgentRunTelemetryService.TelemetryRun telemetryRun =
                new AgentRunTelemetryService.TelemetryRun(null, "trace-3");
        when(agentRunTelemetryService.startRun("session-2", 1L, 22L)).thenReturn(telemetryRun);
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "standard-chat", "trace-3"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-2", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-2", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 服务调用失败")), events);
        verify(agentRunService).failRun(66L, "boom");
        verify(agentRunTelemetryService).markFailure(telemetryRun, runtimeException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit()
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit(),
                null,
                chatStreamEventBridge
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                null
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                new ChatStreamEventBridge()
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        return new ChatServiceImpl(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                chatStreamEventBridge
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            ChatStreamEventBridge chatStreamEventBridge,
            List<ChatAgentExecutor> executors
    ) {
        AgentRegistry agentRegistry = new AgentRegistry(List.of(
                new AgentDefinition(
                        "standard-chat",
                        "普通聊天",
                        "通用",
                        List.of("聊天"),
                        "普通聊天",
                        hAssistant,
                        AgentRuntimeType.STANDARD_STREAMING_CHAT,
                        true
                ),
                new AgentDefinition(
                        "car-rental-assistant",
                        "租车应急协助 Agent",
                        "出行服务",
                        List.of("应急"),
                        "面向租车客户的拖车与紧急事件协助",
                        new Object(),
                        AgentRuntimeType.AGENTIC_SYNC,
                        true
                )
        ));
        return new ChatServiceImpl(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                chatStreamEventBridge,
                agentRegistry,
                executors
        );
    }

    private void invokeRunChatStream(
            ChatServiceImpl chatService,
            FluxSink<ChatStreamEvent> sink,
            ChatStreamConcurrencyGuard.Permit permit,
            Long userId,
            Long promptId,
            String sessionId,
            String userMessage
    ) throws Exception {
        Method method = ChatServiceImpl.class.getDeclaredMethod(
                "runChatStream",
                FluxSink.class,
                ChatStreamConcurrencyGuard.Permit.class,
                Long.class,
                Long.class,
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        try {
            method.invoke(chatService, sink, permit, userId, promptId, null, sessionId, userMessage);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private static class DirectExecutorService extends AbstractExecutorService {
        private final AtomicBoolean shutdown = new AtomicBoolean();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RecordingDirectExecutorService extends DirectExecutorService {
        private final AtomicInteger submitted = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submitted.incrementAndGet();
            super.execute(command);
        }

        int submittedCount() {
            return submitted.get();
        }
    }

    private static final class RecordingExecutorService extends AbstractExecutorService {
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicBoolean shutdown = new AtomicBoolean();

        @Override
        public void shutdown() {
            shutdown.set(true);
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown.set(true);
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown.get();
        }

        @Override
        public boolean isTerminated() {
            return shutdown.get();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            submitted.incrementAndGet();
        }

        int submittedCount() {
            return submitted.get();
        }
    }

    private static final class RejectingExecutorService extends DirectExecutorService {
        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("closed");
        }
    }

    private static final class RecordingPermit implements ChatStreamConcurrencyGuard.Permit {
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final CountDownLatch releasedLatch = new CountDownLatch(1);

        @Override
        public boolean acquired() {
            return true;
        }

        @Override
        public String message() {
            return "";
        }

        @Override
        public void release() {
            released.set(true);
            releasedLatch.countDown();
        }

        @Override
        public void renew() {
            renewCalls.incrementAndGet();
        }

        boolean released() {
            return released.get();
        }

        int renewCalls() {
            return renewCalls.get();
        }

        boolean awaitReleased(long timeout, TimeUnit unit) throws InterruptedException {
            return releasedLatch.await(timeout, unit);
        }
    }

    private record RejectedPermit(String message) implements ChatStreamConcurrencyGuard.Permit {
        @Override
        public boolean acquired() {
            return false;
        }

        @Override
        public void renew() {
        }

        @Override
        public void release() {
        }
    }

    private static final class RecordingChatAgentExecutor implements ChatAgentExecutor {
        private final AgentRuntimeType runtimeType;
        private ChatAgentExecutionCommand command;

        private RecordingChatAgentExecutor(AgentRuntimeType runtimeType) {
            this.runtimeType = runtimeType;
        }

        @Override
        public AgentRuntimeType runtimeType() {
            return runtimeType;
        }

        @Override
        public void execute(ChatAgentExecutionCommand command) {
            this.command = command;
            command.sink().next(new ChatStreamEvent("chunk", "agentic-ok"));
            command.sink().next(new ChatStreamEvent("done", ""));
            command.sink().complete();
            command.onTerminal().run();
        }
    }

    private static final class FakeTokenStream implements TokenStream {
        private final List<String> thinkings = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private Throwable error;
        private Throwable errorAfterThinking;
        private RuntimeException startError;
        private ToolExecution toolExecution;
        private Runnable imagePublisher;
        private Consumer<PartialThinking> partialThinkingHandler;
        private Consumer<String> partialResponseHandler;
        private Consumer<ToolExecution> toolExecutionHandler;
        private Consumer<ChatResponse> completeResponseHandler;
        private Consumer<Throwable> errorHandler;

        FakeTokenStream emitThinking(String thinking) {
            this.thinkings.add(thinking);
            return this;
        }

        FakeTokenStream emitText(String text) {
            this.texts.add(text);
            return this;
        }

        FakeTokenStream emitError(Throwable error) {
            this.error = error;
            return this;
        }

        FakeTokenStream emitErrorAfterThinking(Throwable errorAfterThinking) {
            this.errorAfterThinking = errorAfterThinking;
            return this;
        }

        FakeTokenStream emitStartError(RuntimeException startError) {
            this.startError = startError;
            return this;
        }

        FakeTokenStream emitImageMessage(Runnable imagePublisher) {
            this.imagePublisher = imagePublisher;
            return this;
        }

        FakeTokenStream emitTool(String toolName) {
            this.toolExecution = ToolExecution.builder()
                    .request(ToolExecutionRequest.builder()
                            .id("tool-1")
                            .name(toolName)
                            .arguments("{}")
                            .build())
                    .result("ok")
                    .invocationContext(InvocationContext.builder()
                            .invocationId(UUID.randomUUID())
                            .interfaceName("com.h.backend.chat.ai.HAssistant")
                            .methodName("streamChat")
                            .methodArguments(List.of("hello"))
                            .chatMemoryId("memory-1")
                            .invocationParameters(new InvocationParameters())
                            .timestamp(Instant.now())
                            .build())
                    .build();
            return this;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
            this.partialResponseHandler = partialResponseHandler;
            return this;
        }

        @Override
        public TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler) {
            this.partialThinkingHandler = partialThinkingHandler;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<dev.langchain4j.rag.content.Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
            this.toolExecutionHandler = toolExecuteHandler;
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
            this.completeResponseHandler = completeResponseHandler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            if (startError != null) {
                throw startError;
            }
            if (error != null) {
                if (errorHandler != null) {
                    errorHandler.accept(error);
                }
                return;
            }
            for (String thinking : thinkings) {
                if (partialThinkingHandler != null) {
                    partialThinkingHandler.accept(new PartialThinking(thinking));
                }
            }
            if (errorAfterThinking != null) {
                if (errorHandler != null) {
                    errorHandler.accept(errorAfterThinking);
                }
                return;
            }
            for (String text : texts) {
                if (partialResponseHandler != null) {
                    partialResponseHandler.accept(text);
                }
            }
            if (toolExecution != null && toolExecutionHandler != null) {
                toolExecutionHandler.accept(toolExecution);
            }
            if (imagePublisher != null) {
                imagePublisher.run();
            }
            if (completeResponseHandler != null) {
                completeResponseHandler.accept(mock(ChatResponse.class));
            }
        }
    }

    private static final class ControlledAsyncTokenStream implements TokenStream {
        private final String text;
        private final CountDownLatch firstChunkLatch = new CountDownLatch(1);
        private final CountDownLatch finishLatch = new CountDownLatch(1);
        private final CountDownLatch completionLatch = new CountDownLatch(1);
        private Consumer<String> partialResponseHandler;
        private Consumer<ChatResponse> completeResponseHandler;
        private Consumer<Throwable> errorHandler;

        private ControlledAsyncTokenStream(String text) {
            this.text = text;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
            this.partialResponseHandler = partialResponseHandler;
            return this;
        }

        @Override
        public TokenStream onPartialThinking(Consumer<PartialThinking> partialThinkingHandler) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<dev.langchain4j.rag.content.Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> completeResponseHandler) {
            this.completeResponseHandler = completeResponseHandler;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            Thread.ofVirtual().start(() -> {
                try {
                    if (partialResponseHandler != null) {
                        partialResponseHandler.accept(text);
                    }
                    firstChunkLatch.countDown();
                    finishLatch.await(1, TimeUnit.SECONDS);
                    if (completeResponseHandler != null) {
                        completeResponseHandler.accept(mock(ChatResponse.class));
                    }
                } catch (Throwable error) {
                    if (errorHandler != null) {
                        errorHandler.accept(error);
                    }
                } finally {
                    completionLatch.countDown();
                }
            });
        }

        boolean awaitFirstChunk(long timeout, TimeUnit unit) throws InterruptedException {
            return firstChunkLatch.await(timeout, unit);
        }

        void finishSuccessfully() {
            finishLatch.countDown();
        }

        boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
            return completionLatch.await(timeout, unit);
        }
    }
}

package com.h.backend.chat;

import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.domain.agent.ChatAgentExecutor;
import com.h.backend.chat.domain.agent.HAssistantStreamingExecutor;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessSubagentTurnStart;
import com.h.backend.chat.interfaces.dto.HarnessSubagentStatus;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatStreamConcurrencyGuard;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.chat.application.impl.ChatServiceImpl;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.time.Instant;
import java.time.LocalDateTime;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
        AgentObservability observability = mock(AgentObservability.class);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> new RejectedPermit("当前系统繁忙，请稍后再试")
        );

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-busy", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "当前系统繁忙，请稍后再试")), events);
        verify(hAssistant, never()).streamChat(any(), any(), any());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(chatSessionService, never()).appendUserMessage(any(), any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldEmitBusinessErrorWhenAgentIsUnknown() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, 2L, "missing-agent", "session-unknown", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "领域 Agent 不存在或未启用")), events);
        assertEquals(false, permit.released());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        verify(observability, never()).start(any());
    }

    @Test
    void shouldGenerateImageForSlashImageCommandWithoutCallingAssistant() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        AtomicInteger guardCalls = new AtomicInteger();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
                        "GENERATED",
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
        ChatSessionMessageDto userMessage = new ChatSessionMessageDto(
                "101",
                "user",
                "USER",
                "/image 一只白猫",
                null,
                List.of(),
                java.time.LocalDateTime.now()
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("/image 一只白猫"), any())).thenReturn(101L);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(userMessage);
        when(imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "COMMAND"
        ))).thenReturn(imageMessage);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "/image 一只白猫", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("user_message", "", userMessage),
                new ChatStreamEvent("image", "", imageMessage),
                new ChatStreamEvent("done", "")
        ), events);
        verify(chatSessionService).appendUserMessage(eq(1L), eq("session-1"), eq("/image 一只白猫"), any());
        verify(imageGenerationService).generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "COMMAND"
        ));
        verify(hAssistant, never()).streamChat(any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        assertEquals(0, guardCalls.get());
    }

    @Test
    void shouldBypassChatConcurrencyGuardForSlashImageCommand() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        AtomicInteger guardCalls = new AtomicInteger();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
        ChatSessionMessageDto userMessage = new ChatSessionMessageDto(
                "101",
                "user",
                "USER",
                "/image 给我生成一张柴犬的图片",
                null,
                List.of(),
                java.time.LocalDateTime.now()
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("/image 给我生成一张柴犬的图片"), any())).thenReturn(101L);
        when(chatSessionService.getOwnedMessage(1L, "session-1", 101L)).thenReturn(userMessage);
        when(imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "给我生成一张柴犬的图片",
                "COMMAND"
        ))).thenReturn(imageMessage);

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, 2L, null, "session-1", "/image 给我生成一张柴犬的图片", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("user_message", "", userMessage),
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
        verify(hAssistant, never()).streamChat(any(), any(), any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldSubmitAgentWorkflowToExecutorAndReleasePermitWhenComplete() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("hello");
        RecordingDirectExecutorService executor = new RecordingDirectExecutorService();
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                executor,
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-submit"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-submit", "hello")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(observation.traceId()).thenReturn("trace-submit");
        when(agentRunService.createRun("session-submit", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-submit"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-submit", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "hello"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        assertEquals(1, executor.submittedCount());
        assertTrue(permit.released());
        verify(agentRunService).updateTraceId(55L, "trace-submit");
    }

    @Test
    void shouldEmitUserMessageEventAfterAppendingUserMessage() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("你好呀");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit()
        );

        ChatSessionMessageDto userMessage = new ChatSessionMessageDto(
                "101", "user", "USER", "你好", null, List.of(), java.time.LocalDateTime.now()
        );
        ChatSessionMessageDto assistantMessage = new ChatSessionMessageDto(
                "202", "assistant", "AI", "你好呀", null, List.of(), java.time.LocalDateTime.now()
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-call"), eq("你好"), any())).thenReturn(101L);
        when(chatSessionService.getOwnedMessage(1L, "session-call", 101L)).thenReturn(userMessage);
        when(chatSessionService.appendAssistantMessage(1L, "session-call", "你好呀")).thenReturn(202L);
        when(chatSessionService.getOwnedMessage(1L, "session-call", 202L)).thenReturn(assistantMessage);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-call", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-call"), eq("你好"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "standard-chat", "session-call", "你好", null)
                .collectList()
                .block();

        assertEquals("user_message", events.get(0).type());
        assertEquals(userMessage, events.get(0).message());
        assertEquals("chunk", events.get(1).type());
        assertEquals("done", events.get(2).type());
        assertEquals(assistantMessage, events.get(2).message());
    }

    @Test
    void shouldReleasePermitWhenStreamingChunksArrive() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("he").emitText("llo");
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-stream"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-stream", "hello")).thenReturn(202L);
        when(agentRunService.createRun("session-stream", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-stream"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-stream", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "he"),
                new ChatStreamEvent("chunk", "llo"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        assertTrue(permit.released());
    }

    @Test
    void shouldContinueFinalizingRunAfterSubscriberCancels() throws InterruptedException {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        ControlledAsyncTokenStream tokenStream = new ControlledAsyncTokenStream("hello");
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> permit
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-cancel"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-cancel", "hello")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-cancel", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-cancel"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> receivedEvents = Collections.synchronizedList(new ArrayList<>());
        Disposable[] subscriptionRef = new Disposable[1];
        subscriptionRef[0] = chatService.streamChat(1L, 2L, null, "session-cancel", "hello", null)
                .subscribe(event -> {
                    receivedEvents.add(event);
                    if ("chunk".equals(event.type())) {
                        subscriptionRef[0].dispose();
                    }
                });

        assertTrue(tokenStream.awaitFirstChunk(1, TimeUnit.SECONDS));
        tokenStream.finishSuccessfully();

        verify(agentRunService, org.mockito.Mockito.timeout(1000)).completeRun(55L, 202L);
        verify(observation, org.mockito.Mockito.timeout(1000)).succeed(any());
        verify(chatSessionService, org.mockito.Mockito.timeout(1000))
                .appendAssistantMessage(1L, "session-cancel", "hello");
        assertTrue(tokenStream.awaitCompletion(1, TimeUnit.SECONDS));
        assertTrue(permit.awaitReleased(1, TimeUnit.SECONDS));
        assertEquals(List.of(
                new ChatStreamEvent("user_message", ""),
                new ChatStreamEvent("chunk", "hello")
        ), receivedEvents);
    }

    @Test
    void shouldNotRunAgentSetupOnSubscriptionThreadBeforeExecutorRuns() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RecordingExecutorService executor = new RecordingExecutorService();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                executor,
                (sessionId, userId) -> new RecordingPermit()
        );

        chatService.streamChat(1L, 2L, null, "session-async", "hello", null).subscribe();

        assertEquals(1, executor.submittedCount());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any(), any());
        verify(systemPromptService, never()).resolvePromptId(any(), any());
        verify(chatSessionService, never()).appendUserMessage(any(), any(), any(), any());
        verify(observability, never()).start(any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
        verify(hAssistant, never()).streamChat(any(), any(), any());
    }

    @Test
    void shouldReleasePermitWhenExecutorRejectsAgentWorkflow() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RecordingPermit permit = new RecordingPermit();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new RejectingExecutorService(),
                (sessionId, userId) -> permit
        );

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-rejected", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 服务调用失败")), events);
        assertTrue(permit.released());
        verify(hAssistant, never()).streamChat(any(), any(), any());
    }

    @Test
    void shouldRouteCarRentalAgentToAgenticSyncExecutorWithoutResolvingPrompt() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RecordingChatAgentExecutor agenticExecutor = new RecordingChatAgentExecutor(AgentRuntimeType.AGENTIC_SYNC);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit(),
                null,
                new ChatStreamEventBridge(),
                List.of(agenticExecutor)
        );

        when(chatSessionService.appendUserMessage(eq(1L), eq("session-car"), eq("need towing"), any())).thenReturn(101L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-car", 1L, null, 101L, "car-rental-assistant", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));

        List<ChatStreamEvent> events = chatService
                .streamChat(1L, null, "car-rental-assistant", "session-car", "need towing", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "agentic-ok"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        verify(chatSessionService).assertActiveSession(1L, "session-car", null, "car-rental-assistant");
        verify(systemPromptService, never()).resolvePromptId(any(), any());
        verify(chatSessionService).appendUserMessage(eq(1L), eq("session-car"), eq("need towing"), any());
        verify(agentRunService).createRun("session-car", 1L, null, 101L, "car-rental-assistant", null);
        verifyNoInteractions(hAssistant);
        assertEquals(
                "exec:v2:user:1:session:session-car:agent:car-rental-assistant",
                agenticExecutor.command.memoryId()
        );
        assertEquals("car-rental-assistant", agenticExecutor.command.agent().agentId());
    }

    @Test
    void shouldResolveActualSubagentSessionAndForwardInternalGatewayAddress() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        RecordingChatAgentExecutor harnessExecutor =
                new RecordingChatAgentExecutor(AgentRuntimeType.HARNESS_STREAMING);
        Object harnessBean = new Object();
        AgentRegistry agentRegistry = new AgentRegistry(List.of(new AgentDefinition(
                "harness-agent",
                "协作 Agent",
                "通用",
                List.of("协作"),
                "多 Agent 协作",
                harnessBean,
                AgentRuntimeType.HARNESS_STREAMING,
                true
        )));
        AtomicReference<String> guardedSessionId = new AtomicReference<>();
        ChatServiceImpl chatService = new ChatServiceImpl(
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> {
                    guardedSessionId.set(sessionId);
                    return new RecordingPermit();
                },
                null,
                agentRegistry,
                new ChatMemoryIdFactory(),
                collaborationService,
                List.of(harnessExecutor)
        );
        HarnessSubagentSummaryDto running = new HarnessSubagentSummaryDto(
                "child-runtime-research", "session-harness",
                "资料收集", "补充官方来源", HarnessSubagentStatus.RUNNING,
                0, LocalDateTime.now()
        );
        when(collaborationService.resolveExecutionSession(1L, "child-runtime-research"))
                .thenReturn(new com.h.backend.chat.application.HarnessExecutionSession(
                        "session-harness", "child-runtime-research", "research-child",
                        "session-harness", "research-agent", "补充官方来源"
                ));
        List<ChatMessageResourceUseDto> resources = List.of(
                new ChatMessageResourceUseDto("resource-1", "REFERENCE", "UPLOAD")
        );
        when(collaborationService.beginSubagentTurn(
                1L, "session-harness", "child-runtime-research", "补充官方来源", resources
        )).thenReturn(new HarnessSubagentTurnStart(301L, "execution-child", running));
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun(
                "child-runtime-research", 1L, null, 301L, "harness-agent", null
        )).thenReturn(new AgentRunService.AgentRunHandle(55L));

        chatService.streamChat(
                1L, null, "harness-agent", "child-runtime-research", "补充官方来源", resources
        ).collectList().block();

        verify(chatSessionService, never()).appendUserMessage(any(), any(), any(), any());
        verify(collaborationService).beginSubagentTurn(
                1L, "session-harness", "child-runtime-research", "补充官方来源", resources
        );
        assertEquals("child-runtime-research", harnessExecutor.command.sessionId());
        assertEquals("session-harness", harnessExecutor.command.rootSessionId());
        assertEquals("research-child", harnessExecutor.command.gatewaySubagentId());
        assertEquals("research-agent", harnessExecutor.command.subagentAgentId());
        assertEquals("session-harness", harnessExecutor.command.subagentParentSessionId());
        assertEquals("补充官方来源", harnessExecutor.command.subagentAssignment());
        assertEquals("execution-child", harnessExecutor.command.subagentExecutionId());
        assertEquals("child-runtime-research", guardedSessionId.get());
    }

    @Test
    void shouldMarkSubagentFailedWhenRunPreparationFailsAfterTurnStarted() {
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        HarnessCollaborationService collaborationService = mock(HarnessCollaborationService.class);
        RecordingChatAgentExecutor harnessExecutor =
                new RecordingChatAgentExecutor(AgentRuntimeType.HARNESS_STREAMING);
        AgentRegistry agentRegistry = new AgentRegistry(List.of(new AgentDefinition(
                "harness-agent",
                "协作 Agent",
                "通用",
                List.of("协作"),
                "多 Agent 协作",
                new Object(),
                AgentRuntimeType.HARNESS_STREAMING,
                true
        )));
        ChatServiceImpl chatService = new ChatServiceImpl(
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit(),
                null,
                agentRegistry,
                new ChatMemoryIdFactory(),
                collaborationService,
                List.of(harnessExecutor)
        );
        HarnessSubagentSummaryDto running = new HarnessSubagentSummaryDto(
                "child-runtime-research", "session-harness",
                "资料收集", "补充官方来源", HarnessSubagentStatus.RUNNING,
                0, LocalDateTime.now()
        );
        when(collaborationService.resolveExecutionSession(1L, "child-runtime-research"))
                .thenReturn(new com.h.backend.chat.application.HarnessExecutionSession(
                        "session-harness", "child-runtime-research", "research-child",
                        "session-harness", "research-agent", "补充官方来源"
                ));
        when(collaborationService.beginSubagentTurn(
                1L, "session-harness", "child-runtime-research", "补充官方来源", null
        )).thenReturn(new HarnessSubagentTurnStart(301L, "execution-preparation", running));
        when(agentRunService.createRun(
                "child-runtime-research", 1L, null, 301L, "harness-agent", null
        )).thenThrow(new IllegalStateException("run persistence unavailable"));

        List<ChatStreamEvent> events = chatService.streamChat(
                1L, null, "harness-agent", "child-runtime-research", "补充官方来源", null
        ).collectList().block();

        assertEquals("error", events.get(events.size() - 1).type());
        verify(collaborationService).failSubagent(
                1L, "session-harness", "child-runtime-research", "execution-preparation",
                com.h.backend.chat.application.HarnessSubagentFailureReason.PREPARATION_ERROR,
                "run persistence unavailable"
        );
        assertEquals(null, harnessExecutor.command);
    }

    @Test
    void shouldEmitReasoningEventsAndPersistReasoningBeforeAssistantReply() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
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
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。")).thenReturn(201L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "最终答案")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-1"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("reasoning", "先明确目标。"),
                new ChatStreamEvent("reasoning", "再列实现步骤。"),
                new ChatStreamEvent("chunk", "最终"),
                new ChatStreamEvent("chunk", "答案"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        var inOrder = inOrder(chatSessionService);
        inOrder.verify(chatSessionService).appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any());
        inOrder.verify(chatSessionService).appendReasoningMessage(1L, "session-1", "先明确目标。再列实现步骤。");
        inOrder.verify(chatSessionService).appendAssistantMessage(1L, "session-1", "最终答案");
        verify(agentRunService).completeRun(55L, 202L);
        verify(observation).succeed(any());
    }

    @Test
    void shouldLogCompletedStreamResponseWhenAssistantFinishes() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitThinking("推理")
                .emitText("最终")
                .emitText("答案");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );
        ListAppender<ILoggingEvent> appender = attachListAppender(HAssistantStreamingExecutor.class);

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-log"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendReasoningMessage(1L, "session-log", "推理")).thenReturn(201L);
        when(chatSessionService.appendAssistantMessage(1L, "session-log", "最终答案")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-log", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-log"), eq("hello"), any())).thenReturn(tokenStream);

        try {
            chatService.streamChat(1L, 2L, null, "session-log", "hello", null)
                    .collectList()
                    .block();
        } finally {
            detachListAppender(HAssistantStreamingExecutor.class, appender);
        }

        ILoggingEvent completedLog = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("Chat stream completed"))
                .findFirst()
                .orElse(null);
        assertNotNull(completedLog);
        assertEquals(Level.INFO, completedLog.getLevel());
        assertTrue(completedLog.getFormattedMessage().contains("memoryId=1:22:session-log"));
        assertTrue(completedLog.getFormattedMessage().contains("reasoning=推理"));
        assertTrue(completedLog.getFormattedMessage().contains("reply=最终答案"));
    }

    @Test
    void shouldNotPersistReasoningWhenRuntimeErrorOccursAfterThinking() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RuntimeException runtimeException = new RuntimeException("boom");
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitThinking("先分析")
                .emitErrorAfterThinking(runtimeException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-2"), eq("hello"), any())).thenReturn(111L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat(eq("1:22:session-2"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-2", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("reasoning", "先分析"),
                new ChatStreamEvent("error", "AI 服务调用失败")
        ), eventsAfterUserMessage(events));
        verify(chatSessionService, never()).appendReasoningMessage(any(), any(), any());
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitChunkEventsAndDoneEventForSuccessfulStream() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("he").emitText("llo");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "hello")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-1"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "he"),
                new ChatStreamEvent("chunk", "llo"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        verify(chatSessionService).appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any());
        verify(observability).start(any());
        verify(agentRunService).createRun("session-1", 1L, 22L, 101L, "standard-chat", null);
        verify(chatSessionService).appendAssistantMessage(1L, "session-1", "hello");
        verify(agentRunService).completeRun(55L, 202L);
        verify(observation).succeed(any());
    }

    @Test
    void shouldRecordToolUsageDuringStreamChat() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream()
                .emitTool("search_web")
                .emitText("hello");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any())).thenReturn(101L);
        when(chatSessionService.appendAssistantMessage(1L, "session-1", "hello")).thenReturn(202L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-1"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "hello"),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        verify(agentRunService).recordToolUsage(55L, "search_web");
        verify(agentRunService).completeRun(55L, 202L);
    }

    @Test
    void shouldEmitErrorEventWhenModelMissing() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(new ModelDisabledException("disabled"));
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-1"), eq("hello"), any())).thenReturn(101L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat(eq("1:22:session-1"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-1", "hello", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY")),
                eventsAfterUserMessage(events)
        );
        verify(agentRunService).failRun(55L, "AI 服务未配置 OPENAI_API_KEY");
        verify(observation).fail(tokenStream.error);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldNotExecuteSideEffectsBeforeSubscription() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        chatService.streamChat(1L, 2L, null, "session-lazy", "hello", null);

        verify(chatSessionService, never()).appendUserMessage(any(), any(), any(), any());
        verify(observability, never()).start(any());
        verify(agentRunService, never()).createRun(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldEmitErrorEventWhenStreamCompletesWithoutText() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream();
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-empty"), eq("hello"), any())).thenReturn(121L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-empty", 1L, 22L, 121L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat(eq("1:22:session-empty"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-empty", "hello", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("error", "AI 未返回有效内容")),
                eventsAfterUserMessage(events)
        );
        verify(agentRunService).failRun(88L, "AI 未返回有效内容");
        verify(observation).fail(
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
        AgentObservability observability = mock(AgentObservability.class);
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
                observability,
                chatStreamEventBridge
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-image-tool"), eq("画一只白猫"), any())).thenReturn(121L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-image-tool", 1L, 22L, 121L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat(eq("1:22:session-image-tool"), eq("画一只白猫"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-image-tool", "画一只白猫", null)
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("image", "", imageMessage),
                new ChatStreamEvent("done", "")
        ), eventsAfterUserMessage(events));
        verify(agentRunService).recordToolUsage(88L, "generateImage");
        verify(agentRunService).completeRun(88L, null);
        verify(observation).succeed(any());
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
        verify(agentRunService, never()).failRun(any(), any());
    }

    @Test
    void shouldExposeAttachedImageResourceIdToStandardAssistant() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("好的");
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );
        List<ChatMessageResourceUseDto> resources = List.of(
                new ChatMessageResourceUseDto("resource-attach-1", "ATTACHMENT", "UPLOAD")
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-edit-image"), eq("把背景色改为白色"), any()))
                .thenReturn(121L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-edit-image", 1L, 22L, 121L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat(eq("1:22:session-edit-image"), argThat(message ->
                message.contains("把背景色改为白色")
                        && message.contains("resource-attach-1")
                        && message.contains("generateImage")
        ), any())).thenReturn(tokenStream);

        chatService.streamChat(1L, 2L, "standard-chat", "session-edit-image", "把背景色改为白色", resources)
                .collectList()
                .block();

        verify(hAssistant).streamChat(eq("1:22:session-edit-image"), argThat(message ->
                message.contains("把背景色改为白色")
                        && message.contains("resource-attach-1")
                        && message.contains("generateImage")
        ), any());
    }

    @Test
    void shouldEmitBlockedEventWhenGuardrailMessageIsBlank() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        InputGuardrailException guardrailException = new InputGuardrailException("   ");
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-blank"), eq("hello"), any())).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-blank", "平台检测到您的消息不符合使用规范，已自动拦截。"))
                .thenReturn(303L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-blank", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(77L));
        when(hAssistant.streamChat(eq("1:22:session-blank"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-blank", "hello", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("blocked", "平台检测到您的消息不符合使用规范，已自动拦截。")),
                eventsAfterUserMessage(events)
        );
        verify(chatSessionService).appendBlockedMessage(1L, "session-blank", "平台检测到您的消息不符合使用规范，已自动拦截。");
        verify(agentRunService).failRun(77L, "平台检测到您的消息不符合使用规范，已自动拦截。");
        verify(observation).fail(guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenGuardrailFails() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.domain.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-guardrail"), eq("杀人"), any())).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-guardrail", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat(eq("1:22:session-guardrail"), eq("杀人"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-guardrail", "杀人", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")),
                eventsAfterUserMessage(events)
        );
        verify(chatSessionService).appendBlockedMessage(1L, "session-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(observation).fail(guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenCreatingStreamFailsGuardrail() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.domain.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-create-guardrail"), eq("杀人"), any())).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-create-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-create-guardrail", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat(eq("1:22:session-create-guardrail"), eq("杀人"), any())).thenThrow(guardrailException);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-create-guardrail", "杀人", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")),
                eventsAfterUserMessage(events)
        );
        verify(chatSessionService).appendBlockedMessage(1L, "session-create-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(observation).fail(guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitBlockedEventWhenStartingStreamFailsGuardrail() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        InputGuardrailException guardrailException = new InputGuardrailException(
                "The guardrail com.h.backend.chat.domain.guardrail.ViolenceInputGuardrail failed with this message: 系统提醒您：请勿使用暴力"
        );
        FakeTokenStream tokenStream = new FakeTokenStream().emitStartError(guardrailException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-start-guardrail"), eq("杀人"), any())).thenReturn(111L);
        when(chatSessionService.appendBlockedMessage(1L, "session-start-guardrail", "系统提醒您：请勿使用暴力"))
                .thenReturn(303L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-start-guardrail", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat(eq("1:22:session-start-guardrail"), eq("杀人"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-start-guardrail", "杀人", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("blocked", "系统提醒您：请勿使用暴力")),
                eventsAfterUserMessage(events)
        );
        verify(chatSessionService).appendBlockedMessage(1L, "session-start-guardrail", "系统提醒您：请勿使用暴力");
        verify(agentRunService).failRun(66L, "系统提醒您：请勿使用暴力");
        verify(observation).fail(guardrailException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldEmitErrorEventWhenRuntimeErrorOccurs() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentObservability observability = mock(AgentObservability.class);
        RuntimeException runtimeException = new RuntimeException("boom");
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(runtimeException);
        ChatServiceImpl chatService = createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability
        );

        when(systemPromptService.resolvePromptId(1L, 2L)).thenReturn(22L);
        when(chatSessionService.appendUserMessage(eq(1L), eq("session-2"), eq("hello"), any())).thenReturn(111L);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        when(observability.start(any())).thenReturn(observation);
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat(eq("1:22:session-2"), eq("hello"), any())).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, null, "session-2", "hello", null)
                .collectList()
                .block();

        assertEquals(
                List.of(new ChatStreamEvent("error", "AI 服务调用失败")),
                eventsAfterUserMessage(events)
        );
        verify(agentRunService).failRun(66L, "boom");
        verify(observation).fail(runtimeException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
                new DirectExecutorService(),
                (sessionId, userId) -> new RecordingPermit()
        );
    }

    private ChatServiceImpl createChatService(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService
    ) {
        return createChatService(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
            AgentObservability observability,
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
                observability,
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
            AgentObservability observability,
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
                observability,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                chatStreamEventBridge,
                agentRegistry,
                executors
        );
    }

    private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachListAppender(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        logger.detachAppender(appender);
        appender.stop();
    }

    private List<ChatStreamEvent> eventsAfterUserMessage(List<ChatStreamEvent> events) {
        assertTrue(events.size() >= 1);
        assertEquals("user_message", events.getFirst().type());
        return events.subList(1, events.size());
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

        boolean released() {
            return released.get();
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
                            .interfaceName("com.h.backend.chat.infrastructure.ai.HAssistant")
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

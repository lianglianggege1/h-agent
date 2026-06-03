package com.h.backend.chat;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.ChatSessionService;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-busy", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "当前系统繁忙，请稍后再试")), events);
        verify(hAssistant, never()).streamChat(any(), any());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any());
        verify(chatSessionService, never()).appendUserMessage(any(), any(), any());
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
        when(agentRunService.createRun("session-submit", 1L, 22L, 101L, "unknown", "trace-submit"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-submit", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-submit", "hello")
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

        chatService.streamChat(1L, 2L, "session-async", "hello").subscribe();

        assertEquals(1, executor.submittedCount());
        verify(chatSessionService, never()).assertActiveSession(any(), any(), any());
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

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-rejected", "hello")
                .collectList()
                .block();

        assertEquals(List.of(new ChatStreamEvent("error", "AI 服务调用失败")), events);
        assertTrue(permit.released());
        verify(hAssistant, never()).streamChat(any(), any());
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
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-reasoning"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
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
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "unknown", "trace-error"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-2", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-2", "hello")
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
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-1"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
                .collectList()
                .block();

        assertEquals(List.of(
                new ChatStreamEvent("chunk", "he"),
                new ChatStreamEvent("chunk", "llo"),
                new ChatStreamEvent("done", "")
        ), events);
        verify(chatSessionService).appendUserMessage(1L, "session-1", "hello");
        verify(agentRunTelemetryService).startRun("session-1", 1L, 22L);
        verify(agentRunService).createRun("session-1", 1L, 22L, 101L, "unknown", "trace-1");
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
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-1"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
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
        when(agentRunService.createRun("session-1", 1L, 22L, 101L, "unknown", "trace-2"))
                .thenReturn(new AgentRunService.AgentRunHandle(55L));
        when(hAssistant.streamChat("1:22:session-1", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-1", "hello")
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

        chatService.streamChat(1L, 2L, "session-lazy", "hello");

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
        when(agentRunService.createRun("session-empty", 1L, 22L, 121L, "unknown", "trace-empty"))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        when(hAssistant.streamChat("1:22:session-empty", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-empty", "hello")
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
        when(agentRunService.createRun("session-blank", 1L, 22L, 111L, "unknown", "trace-blank"))
                .thenReturn(new AgentRunService.AgentRunHandle(77L));
        when(hAssistant.streamChat("1:22:session-blank", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-blank", "hello")
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
        when(agentRunService.createRun("session-guardrail", 1L, 22L, 111L, "unknown", "trace-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-guardrail", "杀人")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-guardrail", "杀人")
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
        when(agentRunService.createRun("session-create-guardrail", 1L, 22L, 111L, "unknown", "trace-create-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-create-guardrail", "杀人")).thenThrow(guardrailException);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-create-guardrail", "杀人")
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
        when(agentRunService.createRun("session-start-guardrail", 1L, 22L, 111L, "unknown", "trace-start-guardrail"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-start-guardrail", "杀人")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-start-guardrail", "杀人")
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
        when(agentRunService.createRun("session-2", 1L, 22L, 111L, "unknown", "trace-3"))
                .thenReturn(new AgentRunService.AgentRunHandle(66L));
        when(hAssistant.streamChat("1:22:session-2", "hello")).thenReturn(tokenStream);

        List<ChatStreamEvent> events = chatService.streamChat(1L, 2L, "session-2", "hello")
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
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        return new ChatServiceImpl(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard
        );
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
        }

        boolean released() {
            return released.get();
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

    private static final class FakeTokenStream implements TokenStream {
        private final List<String> thinkings = new ArrayList<>();
        private final List<String> texts = new ArrayList<>();
        private Throwable error;
        private Throwable errorAfterThinking;
        private RuntimeException startError;
        private ToolExecution toolExecution;
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
            if (completeResponseHandler != null) {
                completeResponseHandler.accept(mock(ChatResponse.class));
            }
        }
    }
}

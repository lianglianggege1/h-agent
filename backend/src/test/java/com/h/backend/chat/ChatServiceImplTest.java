package com.h.backend.chat;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.chat.service.impl.ChatServiceImpl;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplTest {

    @Test
    void shouldPersistUserAssistantAndRunInOrder() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitText("hello");
        ChatServiceImpl chatService = new ChatServiceImpl(
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
        when(hAssistant.chat("1:22:session-1", "hello")).thenReturn(tokenStream);

        String reply = chatService.streamChat(1L, 2L, "session-1", "hello", chunk -> {});

        assertEquals("hello", reply);
        verify(chatSessionService).appendUserMessage(1L, "session-1", "hello");
        verify(agentRunTelemetryService).startRun("session-1", 1L, 22L);
        verify(agentRunService).createRun("session-1", 1L, 22L, 101L, "unknown", "trace-1");
        verify(chatSessionService).appendAssistantMessage(1L, "session-1", "hello");
        verify(agentRunService).completeRun(55L, 202L);
        verify(agentRunTelemetryService).markSuccess(telemetryRun);
    }

    @Test
    void shouldFailRunWhenModelMissing() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(new ModelDisabledException("disabled"));
        ChatServiceImpl chatService = new ChatServiceImpl(
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
        when(hAssistant.chat("1:22:session-1", "hello")).thenReturn(tokenStream);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.streamChat(1L, 2L, "session-1", "hello", chunk -> {}));

        assertEquals(50001, ex.getCode());
        verify(agentRunService).failRun(55L, "AI 服务未配置 OPENAI_API_KEY");
        verify(agentRunTelemetryService).markFailure(telemetryRun, tokenStream.error);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    @Test
    void shouldFailRunWithOriginalErrorMessage() {
        HAssistant hAssistant = mock(HAssistant.class);
        SystemPromptService systemPromptService = mock(SystemPromptService.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        AgentRunService agentRunService = mock(AgentRunService.class);
        AgentRunTelemetryService agentRunTelemetryService = mock(AgentRunTelemetryService.class);
        RuntimeException runtimeException = new RuntimeException("boom");
        FakeTokenStream tokenStream = new FakeTokenStream().emitError(runtimeException);
        ChatServiceImpl chatService = new ChatServiceImpl(
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
        when(hAssistant.chat("1:22:session-2", "hello")).thenReturn(tokenStream);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chatService.streamChat(1L, 2L, "session-2", "hello", chunk -> {}));

        assertEquals(50003, ex.getCode());
        verify(agentRunService).failRun(66L, "boom");
        verify(agentRunTelemetryService).markFailure(telemetryRun, runtimeException);
        verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any());
    }

    private static final class FakeTokenStream implements TokenStream {
        private String text;
        private Throwable error;
        private Consumer<String> partialResponseHandler;
        private Consumer<ChatResponse> completeResponseHandler;
        private Consumer<Throwable> errorHandler;

        FakeTokenStream emitText(String text) {
            this.text = text;
            return this;
        }

        FakeTokenStream emitError(Throwable error) {
            this.error = error;
            return this;
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> partialResponseHandler) {
            this.partialResponseHandler = partialResponseHandler;
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<dev.langchain4j.rag.content.Content>> contentHandler) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<dev.langchain4j.service.tool.ToolExecution> toolExecuteHandler) {
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
            if (error != null) {
                if (errorHandler != null) {
                    errorHandler.accept(error);
                }
                return;
            }
            if (text != null && partialResponseHandler != null) {
                partialResponseHandler.accept(text);
            }
            if (completeResponseHandler != null) {
                completeResponseHandler.accept(mock(ChatResponse.class));
            }
        }
    }
}

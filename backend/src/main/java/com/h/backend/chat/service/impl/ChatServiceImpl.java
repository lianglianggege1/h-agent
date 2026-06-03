package com.h.backend.chat.service.impl;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatService;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.SystemPromptService;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final HAssistant hAssistant;

    private final SystemPromptService systemPromptService;

    private final ChatSessionService chatSessionService;

    private final AgentRunService agentRunService;

    private final AgentRunTelemetryService agentRunTelemetryService;

    private final ExecutorService chatStreamExecutor;

    private final ChatStreamConcurrencyGuard concurrencyGuard;

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        this.hAssistant = hAssistant;
        this.systemPromptService = systemPromptService;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.concurrencyGuard = concurrencyGuard;
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String sessionId, String userMessage) {
        return Flux.defer(() -> {
            ChatStreamConcurrencyGuard.Permit permit = concurrencyGuard.tryAcquire(sessionId, userId);
            if (!permit.acquired()) {
                return Flux.just(new ChatStreamEvent("error", permit.message()));
            }
            return Flux.create(sink -> {
                try {
                    chatStreamExecutor.submit(() ->
                            runChatStream(sink, permit, userId, promptId, sessionId, userMessage));
                } catch (RuntimeException ex) {
                    log.error("Failed to submit chat stream task", ex);
                    permit.release();
                    sink.next(new ChatStreamEvent("error", "AI 服务调用失败"));
                    sink.complete();
                }
            });
        });
    }

    private void runChatStream(
            FluxSink<ChatStreamEvent> sink,
            ChatStreamConcurrencyGuard.Permit permit,
            Long userId,
            Long promptId,
            String sessionId,
            String userMessage
    ) {
        AtomicBoolean permitReleased = new AtomicBoolean();
        AgentRunTelemetryService.TelemetryRun telemetryRun = null;
        AgentRunService.AgentRunHandle runHandle = null;
        try {
            chatSessionService.assertActiveSession(userId, sessionId, promptId);
            Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
            String memoryId = userId + ":" + resolvedPromptId + ":" + sessionId;
            Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage);
            telemetryRun = agentRunTelemetryService.startRun(sessionId, userId, resolvedPromptId);
            runHandle = agentRunService.createRun(
                    sessionId,
                    userId,
                    resolvedPromptId,
                    userMessageId,
                    "unknown",
                    telemetryRun.traceId()
            );

            StringBuilder reasoningBuilder = new StringBuilder();
            StringBuilder replyBuilder = new StringBuilder();

            AgentRunTelemetryService.TelemetryRun streamTelemetryRun = telemetryRun;
            AgentRunService.AgentRunHandle streamRunHandle = runHandle;
            hAssistant.streamChat(memoryId, userMessage)
                    .onPartialThinking(thinking -> {
                        String thinkingText = thinking == null ? "" : thinking.text();
                        if (thinkingText == null || thinkingText.isBlank()) {
                            return;
                        }
                        reasoningBuilder.append(thinkingText);
                        sink.next(new ChatStreamEvent("reasoning", thinkingText));
                    })
                    .onPartialResponse(chunk -> {
                        replyBuilder.append(chunk);
                        sink.next(new ChatStreamEvent("chunk", chunk));
                    })
                    .onToolExecuted(toolExecution -> recordToolUsage(streamRunHandle.id(), toolExecution))
                    .onCompleteResponse(ignored -> {
                        try {
                            String reply = replyBuilder.toString();
                            if (reply.isBlank()) {
                                IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
                                agentRunService.failRun(streamRunHandle.id(), error.getMessage());
                                agentRunTelemetryService.markFailure(streamTelemetryRun, error);
                                sink.next(new ChatStreamEvent("error", "AI 未返回有效内容"));
                                sink.complete();
                                return;
                            }
                            String reasoning = reasoningBuilder.toString();
                            if (!reasoning.isBlank()) {
                                chatSessionService.appendReasoningMessage(userId, sessionId, reasoning);
                            }
                            Long assistantMessageId = chatSessionService.appendAssistantMessage(
                                    userId,
                                    sessionId,
                                    reply
                            );
                            agentRunService.completeRun(streamRunHandle.id(), assistantMessageId);
                            agentRunTelemetryService.markSuccess(streamTelemetryRun);
                            sink.next(new ChatStreamEvent("done", ""));
                            sink.complete();
                        } finally {
                            releasePermitOnce(permit, permitReleased);
                        }
                    })
                    .onError(error -> {
                        try {
                            emitFailureEvent(
                                    sink,
                                    userId,
                                    sessionId,
                                    streamRunHandle.id(),
                                    streamTelemetryRun,
                                    error
                            );
                        } finally {
                            releasePermitOnce(permit, permitReleased);
                        }
                    })
                    .start();
        } catch (Exception ex) {
            try {
                if (runHandle != null && telemetryRun != null) {
                    emitFailureEvent(sink, userId, sessionId, runHandle.id(), telemetryRun, ex);
                } else {
                    log.error("Error preparing chat stream", ex);
                    if (telemetryRun != null) {
                        agentRunTelemetryService.markFailure(telemetryRun, ex);
                    }
                    sink.next(new ChatStreamEvent("error", "AI 服务调用失败"));
                    sink.complete();
                }
            } finally {
                releasePermitOnce(permit, permitReleased);
            }
        }
    }

    private void releasePermitOnce(ChatStreamConcurrencyGuard.Permit permit, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            permit.release();
        }
    }

    private void emitFailureEvent(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            String sessionId,
            Long runId,
            AgentRunTelemetryService.TelemetryRun telemetryRun,
            Throwable error
    ) {
        log.error("Error streaming chat", error);
        if (error instanceof ModelDisabledException) {
            agentRunService.failRun(runId, "AI 服务未配置 OPENAI_API_KEY");
            agentRunTelemetryService.markFailure(telemetryRun, error);
            sink.next(new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY"));
            sink.complete();
            return;
        }
        if (error instanceof InputGuardrailException || error instanceof OutputGuardrailException) {
            String cleanMessage = cleanGuardrailMessage(error.getMessage());
            chatSessionService.appendBlockedMessage(userId, sessionId, cleanMessage);
            agentRunService.failRun(runId, cleanMessage);
            agentRunTelemetryService.markFailure(telemetryRun, error);
            sink.next(new ChatStreamEvent("blocked", cleanMessage));
            sink.complete();
            return;
        }
        agentRunService.failRun(runId, error.getMessage() == null ? "AI 服务调用失败" : error.getMessage());
        agentRunTelemetryService.markFailure(telemetryRun, error);
        sink.next(new ChatStreamEvent("error", "AI 服务调用失败"));
        sink.complete();
    }

    private void recordToolUsage(Long runId, ToolExecution toolExecution) {
        if (toolExecution == null || toolExecution.request() == null) {
            return;
        }
        String toolName = toolExecution.request().name();
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        agentRunService.recordToolUsage(runId, toolName);
    }

    private String cleanGuardrailMessage(String message) {
        if (message == null || message.isBlank()) {
            return "平台检测到您的消息不符合使用规范，已自动拦截。";
        }
        String marker = " failed with this message: ";
        int markerIndex = message.indexOf(marker);
        if (markerIndex >= 0) {
            return message.substring(markerIndex + marker.length()).trim();
        }
        return message.trim();
    }
}

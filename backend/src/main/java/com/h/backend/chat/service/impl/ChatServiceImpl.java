package com.h.backend.chat.service.impl;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.SystemPromptService;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final HAssistant hAssistant;

    private final SystemPromptService systemPromptService;

    private final ChatSessionService chatSessionService;

    private final AgentRunService agentRunService;

    private final AgentRunTelemetryService agentRunTelemetryService;

    private final ImageGenerationService imageGenerationService;

    private final ChatStreamEventBridge chatStreamEventBridge;

    @Autowired
    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ImageGenerationService imageGenerationService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        this.hAssistant = hAssistant;
        this.systemPromptService = systemPromptService;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.imageGenerationService = imageGenerationService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ImageGenerationService imageGenerationService
    ) {
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                imageGenerationService,
                new ChatStreamEventBridge()
        );
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService
    ) {
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                null,
                new ChatStreamEventBridge()
        );
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String sessionId, String userMessage) {
        return Flux.defer(() -> {
            chatSessionService.assertActiveSession(userId, sessionId, promptId);
            Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
            if (isImageCommand(userMessage)) {
                return streamImageCommand(userId, resolvedPromptId, sessionId, userMessage);
            }
            String memoryId = userId + ":" + resolvedPromptId + ":" + sessionId;
            Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage);
            AgentRunTelemetryService.TelemetryRun telemetryRun =
                    agentRunTelemetryService.startRun(sessionId, userId, resolvedPromptId);
            AgentRunService.AgentRunHandle runHandle = agentRunService.createRun(
                    sessionId,
                    userId,
                    resolvedPromptId,
                    userMessageId,
                    "unknown",
                    telemetryRun.traceId()
            );

            return Flux.create(sink -> {
                StringBuilder reasoningBuilder = new StringBuilder();
                StringBuilder replyBuilder = new StringBuilder();

                try {
                    chatStreamEventBridge.withPublisher(memoryId, message ->
                                    sink.next(new ChatStreamEvent("image", "", message)),
                            () -> {
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
                                        .onToolExecuted(toolExecution -> recordToolUsage(runHandle.id(), toolExecution))
                                        .onCompleteResponse(ignored -> {
                                            String reply = replyBuilder.toString();
                                            if (reply.isBlank()) {
                                                IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
                                                agentRunService.failRun(runHandle.id(), error.getMessage());
                                                agentRunTelemetryService.markFailure(telemetryRun, error);
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
                                            agentRunService.completeRun(runHandle.id(), assistantMessageId);
                                            agentRunTelemetryService.markSuccess(telemetryRun);
                                            sink.next(new ChatStreamEvent("done", ""));
                                            sink.complete();
                                        })
                                        .onError(error -> emitFailureEvent(
                                                sink,
                                                userId,
                                                sessionId,
                                                runHandle.id(),
                                                telemetryRun,
                                                error
                                        ))
                                        .start();
                                return null;
                            });
                } catch (Exception ex) {
                    emitFailureEvent(sink, userId, sessionId, runHandle.id(), telemetryRun, ex);
                }
            });
        });
    }

    private Flux<ChatStreamEvent> streamImageCommand(
            Long userId,
            Long resolvedPromptId,
            String sessionId,
            String userMessage
    ) {
        if (imageGenerationService == null) {
            return Flux.just(new ChatStreamEvent("error", "图片生成服务未启用"));
        }
        String imagePrompt = extractImagePrompt(userMessage);
        if (imagePrompt.isBlank()) {
            return Flux.just(new ChatStreamEvent("error", "请输入图片提示词"));
        }
        return Flux.defer(() -> {
            chatSessionService.appendUserMessage(userId, sessionId, userMessage);
            try {
                ChatSessionMessageDto message = imageGenerationService.generateImage(
                        new ImageGenerationService.ImageGenerationCommand(
                                userId,
                                sessionId,
                                resolvedPromptId,
                                imagePrompt,
                                "COMMAND"
                        )
                );
                return Flux.just(
                        new ChatStreamEvent("image", "", message),
                        new ChatStreamEvent("done", "")
                );
            } catch (Exception ex) {
                log.error("Error generating image", ex);
                return Flux.just(new ChatStreamEvent("error", "图片生成失败，请稍后重试"));
            }
        });
    }

    private boolean isImageCommand(String userMessage) {
        return userMessage != null
                && (userMessage.trim().equals("/image") || userMessage.trim().startsWith("/image "));
    }

    private String extractImagePrompt(String userMessage) {
        if (userMessage == null) {
            return "";
        }
        String trimmed = userMessage.trim();
        if (trimmed.equals("/image")) {
            return "";
        }
        return trimmed.substring("/image".length()).trim();
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

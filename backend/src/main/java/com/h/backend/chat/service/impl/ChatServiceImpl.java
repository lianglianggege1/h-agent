package com.h.backend.chat.service.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.backend.chat.agent.AgentDefinition;
import com.h.backend.chat.agent.AgentRegistry;
import com.h.backend.chat.agent.AgentRuntimeType;
import com.h.backend.chat.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.agent.ChatAgentExecutor;
import com.h.backend.chat.agent.HAssistantStreamingExecutor;
import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.SystemPromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private final SystemPromptService systemPromptService;
    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService agentRunTelemetryService;
    private final ExecutorService chatStreamExecutor;
    private final ChatStreamConcurrencyGuard concurrencyGuard;
    private final ImageGenerationService imageGenerationService;
    private final AgentRegistry agentRegistry;
    private final Map<AgentRuntimeType, ChatAgentExecutor> executors;

    @Autowired
    public ChatServiceImpl(
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            AgentRegistry agentRegistry,
            List<ChatAgentExecutor> executors
    ) {
        this.systemPromptService = systemPromptService;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.chatStreamExecutor = chatStreamExecutor;
        this.concurrencyGuard = concurrencyGuard;
        this.imageGenerationService = imageGenerationService;
        this.agentRegistry = agentRegistry;
        this.executors = toExecutorMap(executors);
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                null,
                new ChatStreamEventBridge()
        );
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService
    ) {
        this(
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

    public ChatServiceImpl(
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
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                chatStreamEventBridge,
                new AgentRegistry(List.of(standardAgent(hAssistant))),
                List.of()
        );
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            ChatStreamEventBridge chatStreamEventBridge,
            AgentRegistry agentRegistry,
            List<ChatAgentExecutor> executors
    ) {
        this(
                systemPromptService,
                chatSessionService,
                agentRunService,
                agentRunTelemetryService,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                agentRegistry,
                withStandardExecutorIfMissing(
                        hAssistant,
                        chatSessionService,
                        agentRunService,
                        agentRunTelemetryService,
                        chatStreamEventBridge,
                        executors
                )
        );
    }

    @Override
    public Flux<ChatStreamEvent> streamChat(
            Long userId,
            Long promptId,
            String agentId,
            String sessionId,
            String userMessage
    ) {
        return Flux.defer(() -> {
            ChatStreamConcurrencyGuard.Permit permit = concurrencyGuard.tryAcquire(sessionId, userId);
            if (!permit.acquired()) {
                return Flux.just(new ChatStreamEvent("error", permit.message()));
            }
            return Flux.create(sink -> {
                try {
                    chatStreamExecutor.submit(() ->
                            runChatStream(sink, permit, userId, promptId, agentId, sessionId, userMessage));
                } catch (RuntimeException ex) {
                    log.error("Failed to submit chat stream task", ex);
                    permit.release();
                    emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务调用失败"));
                }
            });
        });
    }

    private void runChatStream(
            FluxSink<ChatStreamEvent> sink,
            ChatStreamConcurrencyGuard.Permit permit,
            Long userId,
            Long promptId,
            String agentId,
            String sessionId,
            String userMessage
    ) {
        AtomicBoolean permitReleased = new AtomicBoolean();
        AgentRunTelemetryService.TelemetryRun telemetryRun = null;
        AgentRunService.AgentRunHandle runHandle = null;
        try {
            AgentDefinition agent = resolveAgent(agentId);
            boolean standardChat = agent.runtimeType() == AgentRuntimeType.STANDARD_STREAMING_CHAT;
            Long promptIdForSessionValidation = standardChat ? promptId : null;
            chatSessionService.assertActiveSession(
                    userId,
                    sessionId,
                    promptIdForSessionValidation,
                    agent.agentId()
            );

            Long resolvedPromptId = standardChat ? systemPromptService.resolvePromptId(userId, promptId) : null;
            if (standardChat && isImageCommand(userMessage)) {
                emitImageCommandEvents(sink, userId, resolvedPromptId, sessionId, userMessage);
                releasePermitOnce(permit, permitReleased);
                return;
            }

            Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage);
            telemetryRun = agentRunTelemetryService.startRun(sessionId, userId, resolvedPromptId);
            runHandle = agentRunService.createRun(
                    sessionId,
                    userId,
                    resolvedPromptId,
                    userMessageId,
                    agent.agentId(),
                    telemetryRun.traceId()
            );

            ChatAgentExecutor executor = executorFor(agent.runtimeType());
            executor.execute(new ChatAgentExecutionCommand(
                    sink,
                    userId,
                    resolvedPromptId,
                    sessionId,
                    userMessage,
                    buildMemoryId(userId, resolvedPromptId, agent.agentId(), sessionId),
                    agent,
                    runHandle,
                    telemetryRun,
                    () -> releasePermitOnce(permit, permitReleased)
            ));
        } catch (Exception ex) {
            try {
                log.error("Error preparing chat stream", ex);
                if (runHandle != null && telemetryRun != null) {
                    agentRunService.failRun(
                            runHandle.id(),
                            ex.getMessage() == null ? "AI 服务调用失败" : ex.getMessage()
                    );
                    agentRunTelemetryService.markFailure(telemetryRun, ex);
                } else if (telemetryRun != null) {
                    agentRunTelemetryService.markFailure(telemetryRun, ex);
                }
                emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务调用失败"));
            } finally {
                releasePermitOnce(permit, permitReleased);
            }
        }
    }

    private AgentDefinition resolveAgent(String agentId) {
        String resolvedAgentId = StringUtils.isBlank(agentId)
                ? AgentRegistry.STANDARD_CHAT_AGENT_ID
                : agentId;
        return agentRegistry.requireEnabled(resolvedAgentId);
    }

    private ChatAgentExecutor executorFor(AgentRuntimeType runtimeType) {
        ChatAgentExecutor executor = executors.get(runtimeType);
        if (executor == null) {
            throw new IllegalStateException("No executor configured for runtime " + runtimeType);
        }
        return executor;
    }

    private String buildMemoryId(Long userId, Long resolvedPromptId, String agentId, String sessionId) {
        String promptSegment = resolvedPromptId == null ? "agent" : String.valueOf(resolvedPromptId);
        return userId + ":" + promptSegment + ":" + agentId + ":" + sessionId;
    }

    private void emitImageCommandEvents(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            Long resolvedPromptId,
            String sessionId,
            String userMessage
    ) {
        if (imageGenerationService == null) {
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "图片生成服务未启用"));
            return;
        }
        String imagePrompt = extractImagePrompt(userMessage);
        if (imagePrompt.isBlank()) {
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "请输入图片提示词"));
            return;
        }
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
            emitIfActive(sink, new ChatStreamEvent("image", "", message));
            emitAndCompleteIfActive(sink, new ChatStreamEvent("done", ""));
        } catch (Exception ex) {
            log.error("Error generating image", ex);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "图片生成失败，请稍后重试"));
        }
    }

    private void releasePermitOnce(ChatStreamConcurrencyGuard.Permit permit, AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            permit.release();
        }
    }

    private void emitIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
        } catch (RuntimeException ex) {
            log.debug("Skipping chat stream event after subscriber cancellation", ex);
        }
    }

    private void emitAndCompleteIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
            sink.complete();
        } catch (RuntimeException ex) {
            log.debug("Skipping chat stream completion after subscriber cancellation", ex);
        }
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

    private static Map<AgentRuntimeType, ChatAgentExecutor> toExecutorMap(List<ChatAgentExecutor> executors) {
        Map<AgentRuntimeType, ChatAgentExecutor> mapped = new EnumMap<>(AgentRuntimeType.class);
        for (ChatAgentExecutor executor : executors) {
            mapped.put(executor.runtimeType(), executor);
        }
        return mapped;
    }

    private static List<ChatAgentExecutor> withStandardExecutorIfMissing(
            HAssistant hAssistant,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ChatStreamEventBridge chatStreamEventBridge,
            List<ChatAgentExecutor> executors
    ) {
        List<ChatAgentExecutor> merged = new ArrayList<>(executors);
        boolean hasStandardExecutor = merged.stream()
                .anyMatch(executor -> executor.runtimeType() == AgentRuntimeType.STANDARD_STREAMING_CHAT);
        if (!hasStandardExecutor) {
            merged.add(new HAssistantStreamingExecutor(
                    hAssistant,
                    chatSessionService,
                    agentRunService,
                    agentRunTelemetryService,
                    chatStreamEventBridge
            ));
        }
        return merged;
    }

    private static AgentDefinition standardAgent(HAssistant hAssistant) {
        return new AgentDefinition(
                AgentRegistry.STANDARD_CHAT_AGENT_ID,
                "普通聊天",
                "通用",
                List.of("聊天", "知识库"),
                "使用系统提示词和知识库的普通聊天助手",
                hAssistant,
                AgentRuntimeType.STANDARD_STREAMING_CHAT,
                true
        );
    }
}

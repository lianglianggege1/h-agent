package com.h.backend.chat.service.impl;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.SystemPromptService;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.stereotype.Service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class ChatServiceImpl implements ChatService {

    private final HAssistant hAssistant;

    private final SystemPromptService systemPromptService;

    private final ChatSessionService chatSessionService;

    private final AgentRunService agentRunService;

    private final AgentRunTelemetryService agentRunTelemetryService;

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService
    ) {
        this.hAssistant = hAssistant;
        this.systemPromptService = systemPromptService;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
    }

    @Override
    public String streamChat(Long userId, Long promptId, String sessionId, String userMessage, Consumer<String> onChunk) {
        chatSessionService.assertActiveSession(userId, sessionId, promptId);
        StringBuilder replyBuilder = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
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

        // h-agent的runtime loop
        hAssistant.streamChat(memoryId, userMessage)
                .onPartialResponse(chunk -> {
                    replyBuilder.append(chunk);
                    onChunk.accept(chunk);
                })
                .onToolExecuted(toolExecution -> recordToolUsage(runHandle.id(), toolExecution))
                .onCompleteResponse(ignored -> latch.countDown())
                .onError(error -> {
                    errorRef.set(error);
                    latch.countDown();
                })
                .start();

        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            agentRunService.failRun(runHandle.id(), "AI 响应被中断");
            agentRunTelemetryService.markFailure(telemetryRun, ex);
            throw new BusinessException(50002, "AI 响应被中断");
        }

        Throwable error = errorRef.get();
        if (error != null) {
            if (error instanceof ModelDisabledException) {
                agentRunService.failRun(runHandle.id(), "AI 服务未配置 OPENAI_API_KEY");
                agentRunTelemetryService.markFailure(telemetryRun, error);
                throw new BusinessException(50001, "AI 服务未配置 OPENAI_API_KEY");
            }
            agentRunService.failRun(runHandle.id(), error.getMessage() == null ? "AI 服务调用失败" : error.getMessage());
            agentRunTelemetryService.markFailure(telemetryRun, error);
            throw new BusinessException(50003, "AI 服务调用失败");
        }

        String reply = replyBuilder.toString();
        if (reply.isBlank()) {
            agentRunService.failRun(runHandle.id(), "AI 未返回有效内容");
            agentRunTelemetryService.markFailure(telemetryRun, new IllegalStateException("AI 未返回有效内容"));
            throw new BusinessException(50004, "AI 未返回有效内容");
        }
        Long assistantMessageId = chatSessionService.appendAssistantMessage(userId, sessionId, reply);
        agentRunService.completeRun(runHandle.id(), assistantMessageId);
        agentRunTelemetryService.markSuccess(telemetryRun);
        return reply;
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
}

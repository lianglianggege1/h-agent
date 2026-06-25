package com.h.backend.chat.agent;

import com.h.backend.chat.ai.HAssistant;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ChatStreamEventBridge;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Component
public class HAssistantStreamingExecutor implements ChatAgentExecutor {

    private final HAssistant hAssistant;
    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService agentRunTelemetryService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public HAssistantStreamingExecutor(
            HAssistant hAssistant,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        this.hAssistant = hAssistant;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.STANDARD_STREAMING_CHAT;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        StringBuilder reasoningBuilder = new StringBuilder();
        StringBuilder replyBuilder = new StringBuilder();
        AtomicBoolean imageEmitted = new AtomicBoolean();
        Consumer<ChatSessionMessageDto> imagePublisher = message -> {
            imageEmitted.set(true);
            emitIfActive(command.sink(), new ChatStreamEvent("image", "", message));
        };
        chatStreamEventBridge.registerPublisher(command.memoryId(), imagePublisher);
        try {
            hAssistant.streamChat(command.memoryId(), command.userMessage())
                    .onPartialThinking(thinking -> {
                        String thinkingText = thinking == null ? "" : thinking.text();
                        if (thinkingText == null || thinkingText.isBlank()) {
                            return;
                        }
                        reasoningBuilder.append(thinkingText);
                        emitIfActive(command.sink(), new ChatStreamEvent("reasoning", thinkingText));
                    })
                    .onPartialResponse(chunk -> {
                        replyBuilder.append(chunk);
                        emitIfActive(command.sink(), new ChatStreamEvent("chunk", chunk));
                    })
                    .onToolExecuted(toolExecution -> {
                        recordToolUsage(command.runHandle().id(), toolExecution);
                    })
                    .onCompleteResponse(ignored -> {
                        try {
                            completeSuccessfulStream(command, reasoningBuilder, replyBuilder, imageEmitted);
                        } finally {
                            chatStreamEventBridge.unregisterPublisher(command.memoryId(), imagePublisher);
                            command.onTerminal().run();
                        }
                    })
                    .onError(error -> {
                        try {
                            emitFailureEvent(command.sink(), command, error);
                        } finally {
                            chatStreamEventBridge.unregisterPublisher(command.memoryId(), imagePublisher);
                            command.onTerminal().run();
                        }
                    })
                    .start();
        } catch (Exception ex) {
            try {
                chatStreamEventBridge.unregisterPublisher(command.memoryId(), imagePublisher);
                emitFailureEvent(command.sink(), command, ex);
            } finally {
                command.onTerminal().run();
            }
        }
    }

    private void completeSuccessfulStream(
            ChatAgentExecutionCommand command,
            StringBuilder reasoningBuilder,
            StringBuilder replyBuilder,
            AtomicBoolean imageEmitted
    ) {
        String reply = replyBuilder.toString();
        if (reply.isBlank()) {
            if (imageEmitted.get()) {
                agentRunService.completeRun(command.runHandle().id(), null);
                agentRunTelemetryService.markSuccess(command.telemetryRun());
                emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", ""));
                return;
            }
            IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
            agentRunService.failRun(command.runHandle().id(), error.getMessage());
            agentRunTelemetryService.markFailure(command.telemetryRun(), error);
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("error", "AI 未返回有效内容"));
            return;
        }
        String reasoning = reasoningBuilder.toString();
        if (!reasoning.isBlank()) {
            chatSessionService.appendReasoningMessage(command.userId(), command.sessionId(), reasoning);
        }
        Long assistantMessageId = chatSessionService.appendAssistantMessage(
                command.userId(),
                command.sessionId(),
                reply
        );
        agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
        agentRunTelemetryService.markSuccess(command.telemetryRun());
        emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", ""));
    }

    private void emitFailureEvent(
            FluxSink<ChatStreamEvent> sink,
            ChatAgentExecutionCommand command,
            Throwable error
    ) {
        log.error("Error streaming chat", error);
        if (error instanceof ModelDisabledException) {
            agentRunService.failRun(command.runHandle().id(), "AI 服务未配置 OPENAI_API_KEY");
            agentRunTelemetryService.markFailure(command.telemetryRun(), error);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY"));
            return;
        }
        if (error instanceof InputGuardrailException || error instanceof OutputGuardrailException) {
            String cleanMessage = cleanGuardrailMessage(error.getMessage());
            chatSessionService.appendBlockedMessage(command.userId(), command.sessionId(), cleanMessage);
            agentRunService.failRun(command.runHandle().id(), cleanMessage);
            agentRunTelemetryService.markFailure(command.telemetryRun(), error);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("blocked", cleanMessage));
            return;
        }
        agentRunService.failRun(
                command.runHandle().id(),
                error.getMessage() == null ? "AI 服务调用失败" : error.getMessage()
        );
        agentRunTelemetryService.markFailure(command.telemetryRun(), error);
        emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务调用失败"));
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

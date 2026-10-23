package com.h.backend.chat.domain.agent;

import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.memory.application.SuccessfulTurnCommitter;
import com.h.backend.memory.domain.MemoryInvocationContext;
import dev.langchain4j.guardrail.InputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.model.ModelDisabledException;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.h.backend.chat.application.agui.ChatExecutionCancellation;
import dev.langchain4j.model.chat.response.StreamingHandle;

@Slf4j
@Component
public class HAssistantStreamingExecutor implements ChatAgentExecutor {

    private final HAssistant hAssistant;
    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final ChatStreamEventBridge chatStreamEventBridge;
    private final SuccessfulTurnCommitter successfulTurnCommitter;

    public HAssistantStreamingExecutor(
            HAssistant hAssistant,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            ChatStreamEventBridge chatStreamEventBridge,
            SuccessfulTurnCommitter successfulTurnCommitter
    ) {
        this.hAssistant = hAssistant;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.chatStreamEventBridge = chatStreamEventBridge;
        this.successfulTurnCommitter = successfulTurnCommitter;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.STANDARD_STREAMING_CHAT;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        StringBuilder reasoningBuilder = new StringBuilder();
        StringBuilder replyBuilder = new StringBuilder();
        AtomicBoolean resourceEmitted = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<StreamingHandle> streamingHandle = new AtomicReference<>();
        ChatExecutionCancellation cancellation = ChatExecutionCancellation.from(command.sink());
        Consumer<ChatSessionMessageDto> resourcePublisher = message -> {
            resourceEmitted.set(true);
            String eventType = "IMAGE".equalsIgnoreCase(message.messageType()) ? "image" : "resource";
            emitIfActive(command.sink(), new ChatStreamEvent(eventType, "", message));
        };
        chatStreamEventBridge.registerPublisher(command.memoryId(), resourcePublisher);
        if (cancellation != null) cancellation.onCancel(() -> {
            if (!terminal.compareAndSet(false, true)) return;
            StreamingHandle handle = streamingHandle.get();
            if (handle != null) handle.cancel();
            chatStreamEventBridge.unregisterPublisher(command.memoryId(), resourcePublisher);
            try {
                agentRunService.failRun(command.runHandle().id(), "用户已停止任务");
                command.observation().cancel("用户已停止任务");
            } finally { command.onTerminal().run(); }
        });
        Consumer<dev.langchain4j.model.chat.response.PartialThinking> thinkingHandler = thinking -> {
            if (terminal.get()) return;
            String text = thinking == null ? "" : thinking.text();
            if (text == null || text.isBlank()) return;
            reasoningBuilder.append(text);
            emitIfActive(command.sink(), new ChatStreamEvent("reasoning", text));
        };
        try {
            ChatExecutionCancellation.check(command.sink());
            String messageForLlm = command.userMessage();
            String referenceResourceId = firstReferenceResourceId(command);
            if (referenceResourceId != null) {
                messageForLlm = command.userMessage()
                        + "\n[系统：用户选择了一张参考图片（资源ID: " + referenceResourceId
                        + "）。请根据用户目标选择工具：生成或修改静态图片时调用 generateImage；让图片中的主体或环境运动，或生成动画、运镜、视频时调用 image_to_video；仅分析或描述图片时不调用生成工具。调用图片或视频生成工具时，将该资源ID作为 referenceResourceId 传入。]";
            }
            // streamChat 在当前线程同步构建首个请求并发起模型调用；执行观测作用域把
            // Generation/Tool Span 挂到本次运行。后续轮次由流式回调中的观测作用域接管。
            try (ObservationScope ignored = command.observation().scope()) {
                dev.langchain4j.service.TokenStream stream = hAssistant.streamChat(
                                command.memoryId(),
                                messageForLlm,
                                memoryInvocationContext(command).toInvocationParameters()
                        )
                        .onToolExecuted(toolExecution -> {
                            if (terminal.get()) return;
                            recordToolUsage(command.runHandle().id(), toolExecution);
                            if (cancellation != null) emitIfActive(command.sink(), new ChatStreamEvent("tool_result", "", null,
                                    Map.of("id", toolExecution.request().id(), "name", toolExecution.request().name(),
                                            "result", java.util.Objects.toString(toolExecution.result(), ""),
                                            "failed", toolExecution.hasFailed())));
                        })
                        .onCompleteResponse(ignoredResponse -> {
                            if (!terminal.compareAndSet(false, true)) return;
                            try {
                                logCompletedStream(command, reasoningBuilder, replyBuilder, resourceEmitted);
                                completeSuccessfulStream(command, reasoningBuilder, replyBuilder, resourceEmitted);
                            } finally {
                                chatStreamEventBridge.unregisterPublisher(command.memoryId(), resourcePublisher);
                                command.onTerminal().run();
                            }
                        })
                        .onError(error -> {
                            if (!terminal.compareAndSet(false, true)) return;
                            try {
                                emitFailureEvent(command.sink(), command, error);
                            } finally {
                                chatStreamEventBridge.unregisterPublisher(command.memoryId(), resourcePublisher);
                                command.onTerminal().run();
                            }
                        });
                if (cancellation == null) {
                    stream.onPartialThinking(thinkingHandler);
                    stream.onPartialResponse(chunk -> {
                        replyBuilder.append(chunk);
                        emitIfActive(command.sink(), new ChatStreamEvent("chunk", chunk));
                    });
                } else {
                    stream.onPartialThinkingWithContext((thinking, context) -> {
                        streamingHandle.set(context.streamingHandle());
                        if (terminal.get()) { context.streamingHandle().cancel(); return; }
                        thinkingHandler.accept(thinking);
                    });
                    stream.onPartialResponseWithContext((response, context) -> {
                        streamingHandle.set(context.streamingHandle());
                        if (terminal.get()) { context.streamingHandle().cancel(); return; }
                        replyBuilder.append(response.text());
                        emitIfActive(command.sink(), new ChatStreamEvent("chunk", response.text()));
                    }).beforeToolExecution(before -> {
                        ChatExecutionCancellation.check(command.sink());
                        emitIfActive(command.sink(), new ChatStreamEvent("tool_start", "", null,
                                Map.of("id", before.request().id(), "name", before.request().name())));
                    });
                }
                ChatExecutionCancellation.check(command.sink());
                stream.start();
            }
        } catch (Exception ex) {
            if (!terminal.compareAndSet(false, true)) return;
            try {
                chatStreamEventBridge.unregisterPublisher(command.memoryId(), resourcePublisher);
                emitFailureEvent(command.sink(), command, ex);
            } finally {
                command.onTerminal().run();
            }
        }
    }

    private void logCompletedStream(
            ChatAgentExecutionCommand command,
            StringBuilder reasoningBuilder,
            StringBuilder replyBuilder,
            AtomicBoolean imageEmitted
    ) {
        log.info(
                "Chat stream completed memoryId={} runId={} imageEmitted={} reasoning={} reply={}",
                command.memoryId(),
                command.runHandle().id(),
                imageEmitted.get(),
                reasoningBuilder,
                replyBuilder
        );
    }

    private String firstReferenceResourceId(ChatAgentExecutionCommand command) {
        if (command.resources() == null || command.resources().isEmpty()) {
            return null;
        }
        return command.resources().stream()
                .filter(resource -> "REFERENCE".equalsIgnoreCase(resource.role())
                        || "ATTACHMENT".equalsIgnoreCase(resource.role()))
                .map(ChatMessageResourceUseDto::resourceId)
                .findFirst()
                .orElse(null);
    }

    private MemoryInvocationContext memoryInvocationContext(ChatAgentExecutionCommand command) {
        return new MemoryInvocationContext(
                command.userId(),
                command.agent().agentId(),
                command.rootSessionId(),
                command.runHandle().id(),
                command.sessionId(),
                command.resolvedPromptId()
        );
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
                command.observation().succeed(null);
                emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", ""));
                return;
            }
            IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
            agentRunService.failRun(command.runHandle().id(), error.getMessage());
            command.observation().fail(error);
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("error", "AI 未返回有效内容"));
            return;
        }
        String reasoning = reasoningBuilder.toString();
        if (!reasoning.isBlank()) {
            chatSessionService.appendReasoningMessage(command.userId(), command.sessionId(), reasoning);
        }
        // assistant message、run success 与 memory capture outbox 同一事务提交
        ChatSessionMessageDto assistantMessage = successfulTurnCommitter.commit(command, reply);
        command.observation().succeed(assistantOutput(reply));
        emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", "", assistantMessage));
    }

    private static SemanticContent assistantOutput(String reply) {
        return SemanticContent.ofMessages(List.of(SemanticMessage.of("assistant", reply)));
    }

    private void emitFailureEvent(
            FluxSink<ChatStreamEvent> sink,
            ChatAgentExecutionCommand command,
            Throwable error
    ) {
        log.error("Error streaming chat", error);
        if (error instanceof ModelDisabledException) {
            agentRunService.failRun(command.runHandle().id(), "AI 服务未配置 OPENAI_API_KEY");
            command.observation().fail(error);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务未配置 OPENAI_API_KEY"));
            return;
        }
        if (error instanceof InputGuardrailException || error instanceof OutputGuardrailException) {
            String cleanMessage = cleanGuardrailMessage(error.getMessage());
            chatSessionService.appendBlockedMessage(command.userId(), command.sessionId(), cleanMessage);
            agentRunService.failRun(command.runHandle().id(), cleanMessage);
            command.observation().fail(error);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("blocked", cleanMessage));
            return;
        }
        agentRunService.failRun(
                command.runHandle().id(),
                error.getMessage() == null ? "AI 服务调用失败" : error.getMessage()
        );
        command.observation().fail(error);
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

package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentScope Harness 与现有聊天执行协议之间的适配器。
 *
 * <p>对用户有意义的 AgentEvent 会映射为 {@code harness_event}，同时只把父 Agent 的文本
 * 与思考降级映射为旧前端已支持的 {@code chunk/reasoning}。这样旧客户端仍能聊天，而
 * 子 Agent 的增量不会被错误拼进父回复。</p>
 */
@Slf4j
@Component
public class HarnessAgentExecutor implements ChatAgentExecutor {

    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService agentRunTelemetryService;
    private final HarnessEventMapper eventMapper;

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            HarnessEventMapper eventMapper
    ) {
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.eventMapper = eventMapper;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.HARNESS_STREAMING;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        HarnessAgent harnessAgent = requireHarnessAgent(command);
        log.info("[HarnessExecutor] Agent执行开始 userId={}, sessionId={}, runId={}",
                command.userId(), command.sessionId(), command.runHandle().id());
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(command.userId()))
                .sessionId(command.sessionId())
                // Harness 2.0.1 只有显式打开该上下文标记才会发 SUBAGENT_EXPOSED。
                .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
                .build();
        Execution execution = new Execution(command);

        command.sink().onCancel(() -> execution.cancel("客户端已断开"));
        try {
            Disposable subscription = harnessAgent
                    .streamEvents(command.userMessage(), runtimeContext)
                    .subscribe(execution::onEvent, execution::onError, execution::onComplete);
            execution.attachSubscription(subscription);
            if (command.sink().isCancelled()) {
                execution.cancel("客户端已断开");
            }
        } catch (RuntimeException ex) {
            execution.onError(ex);
        }
    }

    private HarnessAgent requireHarnessAgent(ChatAgentExecutionCommand command) {
        if (command.agent().agentBean() instanceof HarnessAgent harnessAgent) {
            return harnessAgent;
        }
        throw new IllegalStateException("HARNESS_STREAMING agent bean must be HarnessAgent");
    }

    private final class Execution {

        private final ChatAgentExecutionCommand command;
        private final AtomicLong sequence = new AtomicLong();
        // 用户可见响应与 Harness 后置维护是两个终态；父响应结束后仍允许记忆任务继续运行。
        private final AtomicBoolean responseTerminal = new AtomicBoolean();
        private final AtomicBoolean executionReleased = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean emittedParentText = new AtomicBoolean();
        private final AtomicReference<Msg> parentResult = new AtomicReference<>();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final StringBuilder parentReasoning = new StringBuilder();

        private Execution(ChatAgentExecutionCommand command) {
            this.command = command;
        }

        private void onEvent(AgentEvent event) {
            if (responseTerminal.get() || command.sink().isCancelled()) {
                return;
            }
            // sequence 按原始 AgentEvent 流严格递增；每条事件都通过同一 SSE 信封向前端发送。
            emit(eventMapper.map(command.runHandle().id(), sequence.incrementAndGet(), event));

            // source 为空才是父 Agent。子文本仅保留在 harness_event，避免污染父气泡。
            if (isParent(event) && event instanceof TextBlockDeltaEvent textEvent) {
                emittedParentText.set(true);
                emit(new ChatStreamEvent("chunk", textEvent.getDelta()));
            } else if (isParent(event) && event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
                parentReasoning.append(thinkingEvent.getDelta());
                emit(new ChatStreamEvent("reasoning", thinkingEvent.getDelta()));
            } else if (isParent(event) && event instanceof AgentResultEvent resultEvent) {
                parentResult.set(resultEvent.getResult());
            } else if (isParent(event) && event instanceof AgentEndEvent) {
                completeSuccessfulResponse();
            }
        }

        private void onComplete() {
            // 兼容不发送 AgentEndEvent 的测试 adapter 或未来实现；正常 Harness 路径已在父
            // AgentEndEvent 处完成用户响应，此时这里只表示后台维护也已结束。
            if (responseTerminal.get()) {
                return;
            }
            completeSuccessfulResponse();
        }

        private void completeSuccessfulResponse() {
            if (!responseTerminal.compareAndSet(false, true)) {
                return;
            }
            try {
                Msg result = parentResult.get();
                // AgentResult 是最终权威 Msg；delta 仅用于实时展示，不能作为持久化真相。
                String reply = result == null ? "" : result.getTextContent();
                if (reply == null || reply.isBlank()) {
                    throw new IllegalStateException("AI 未返回有效内容");
                }
                if (!emittedParentText.get()) {
                    emit(new ChatStreamEvent("chunk", reply));
                }
                String reasoning = parentReasoning.toString();
                if (!reasoning.isBlank()) {
                    chatSessionService.appendReasoningMessage(
                            command.userId(),
                            command.sessionId(),
                            reasoning
                    );
                }
                Long assistantMessageId = chatSessionService.appendAssistantMessage(
                        command.userId(),
                        command.sessionId(),
                        reply
                );
                ChatSessionMessageDto assistantMessage = chatSessionService.getOwnedMessage(
                        command.userId(),
                        command.sessionId(),
                        assistantMessageId
                );
                agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
                agentRunTelemetryService.markSuccess(command.telemetryRun());
                log.info("[HarnessExecutor] Agent执行完成 userId={}, sessionId={}, runId={}, replyLength={}",
                        command.userId(), command.sessionId(), command.runHandle().id(), reply.length());
                releaseExecution();
                emit(new ChatStreamEvent("done", "", assistantMessage));
                completeSink();
            } catch (RuntimeException ex) {
                failAfterResponseClaim(ex, true);
            } finally {
                releaseExecution();
            }
        }

        private void onError(Throwable error) {
            if (!responseTerminal.compareAndSet(false, true)) {
                log.warn(
                        "[HarnessExecutor] 用户响应完成后的维护任务失败 userId={}, sessionId={}, runId={}, error={}",
                        command.userId(), command.sessionId(), command.runHandle().id(), error.getMessage(), error
                );
                return;
            }
            try {
                failAfterResponseClaim(error, true);
            } finally {
                releaseExecution();
            }
        }

        private void cancel(String reason) {
            if (!responseTerminal.compareAndSet(false, true)) {
                // Controller 收到 done 后会取消上游 chat Flux。父响应已经提交时不能把这个
                // 传播放大成 Harness 取消，否则会中断刚开始的记忆提取/合并。
                return;
            }
            log.info("[HarnessExecutor] Agent执行取消 userId={}, sessionId={}, runId={}, reason={}",
                    command.userId(), command.sessionId(), command.runHandle().id(), reason);
            cancelRequested.set(true);
            Disposable current = subscription.get();
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
            CancellationException error = new CancellationException(reason);
            try {
                failAfterResponseClaim(error, false);
            } finally {
                releaseExecution();
            }
        }

        private void attachSubscription(Disposable current) {
            subscription.set(current);
            if (cancelRequested.get() && !current.isDisposed()) {
                current.dispose();
            }
        }

        private void failAfterResponseClaim(Throwable error, boolean emitError) {
            log.error("[HarnessExecutor] Agent执行错误 userId={}, sessionId={}, runId={}, error={}",
                    command.userId(), command.sessionId(), command.runHandle().id(), error.getMessage(), error);
            String detail = error.getMessage() == null ? "AI 服务调用失败" : error.getMessage();
            agentRunService.failRun(command.runHandle().id(), detail);
            agentRunTelemetryService.markFailure(command.telemetryRun(), error);
            if (emitError) {
                emit(new ChatStreamEvent("error", "AI 服务调用失败"));
                completeSink();
            }
        }

        private void releaseExecution() {
            if (executionReleased.compareAndSet(false, true)) {
                command.onTerminal().run();
            }
        }

        private boolean isParent(AgentEvent event) {
            return event.getSource() == null || event.getSource().isBlank();
        }

        private void emit(ChatStreamEvent event) {
            FluxSink<ChatStreamEvent> sink = command.sink();
            if (sink.isCancelled()) {
                return;
            }
            try {
                sink.next(event);
            } catch (RuntimeException ex) {
                log.debug("Skipping Harness stream event after subscriber cancellation", ex);
            }
        }

        private void completeSink() {
            if (!command.sink().isCancelled()) {
                command.sink().complete();
            }
        }
    }
}

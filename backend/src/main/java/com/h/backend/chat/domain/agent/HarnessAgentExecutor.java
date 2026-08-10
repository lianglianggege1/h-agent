package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
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
            execution.subscription.set(subscription);
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
        // Reactor 的完成、错误与浏览器取消可能竞争；只允许一个路径落 run 并释放 permit。
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean emittedParentText = new AtomicBoolean();
        private final AtomicReference<Msg> parentResult = new AtomicReference<>();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();

        private Execution(ChatAgentExecutionCommand command) {
            this.command = command;
        }

        private void onEvent(AgentEvent event) {
            if (terminal.get() || command.sink().isCancelled()) {
                return;
            }
            // sequence 按原始 AgentEvent 流严格递增；每条事件都通过同一 SSE 信封向前端发送。
            emit(eventMapper.map(command.runHandle().id(), sequence.incrementAndGet(), event));

            // source 为空才是父 Agent。子文本仅保留在 harness_event，避免污染父气泡。
            if (isParent(event) && event instanceof TextBlockDeltaEvent textEvent) {
                emittedParentText.set(true);
                emit(new ChatStreamEvent("chunk", textEvent.getDelta()));
            } else if (isParent(event) && event instanceof ThinkingBlockDeltaEvent thinkingEvent) {
                emit(new ChatStreamEvent("reasoning", thinkingEvent.getDelta()));
            } else if (isParent(event) && event instanceof AgentResultEvent resultEvent) {
                parentResult.set(resultEvent.getResult());
            }
        }

        private void onComplete() {
            if (!terminal.compareAndSet(false, true)) {
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
                emit(new ChatStreamEvent("done", "", assistantMessage));
                completeSink();
            } catch (RuntimeException ex) {
                failAfterTerminalClaim(ex, true);
            } finally {
                command.onTerminal().run();
            }
        }

        private void onError(Throwable error) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                failAfterTerminalClaim(error, true);
            } finally {
                command.onTerminal().run();
            }
        }

        private void cancel(String reason) {
            Disposable current = subscription.get();
            if (current != null && !current.isDisposed()) {
                current.dispose();
            }
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            CancellationException error = new CancellationException(reason);
            try {
                failAfterTerminalClaim(error, false);
            } finally {
                command.onTerminal().run();
            }
        }

        private void failAfterTerminalClaim(Throwable error, boolean emitError) {
            log.error("Error executing Harness Agent stream", error);
            String detail = error.getMessage() == null ? "AI 服务调用失败" : error.getMessage();
            agentRunService.failRun(command.runHandle().id(), detail);
            agentRunTelemetryService.markFailure(command.telemetryRun(), error);
            if (emitError) {
                emit(new ChatStreamEvent("error", "AI 服务调用失败"));
                completeSink();
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

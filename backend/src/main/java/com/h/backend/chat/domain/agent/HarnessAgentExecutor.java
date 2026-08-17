package com.h.backend.chat.domain.agent;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessSubagentCompletion;
import com.h.backend.chat.application.HarnessSubagentExposure;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final HarnessRuntime harnessRuntime;
    private final HarnessCollaborationService harnessCollaborationService;
    private final HarnessSubagentEventRelay subagentEventRelay;

    @Autowired
    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            HarnessEventMapper eventMapper,
            HarnessRuntime harnessRuntime,
            HarnessCollaborationService harnessCollaborationService,
            HarnessSubagentEventRelay subagentEventRelay
    ) {
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.eventMapper = eventMapper;
        this.harnessRuntime = harnessRuntime;
        this.harnessCollaborationService = harnessCollaborationService;
        this.subagentEventRelay = subagentEventRelay;
    }

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            HarnessEventMapper eventMapper,
            HarnessRuntime harnessRuntime,
            HarnessCollaborationService harnessCollaborationService
    ) {
        this(chatSessionService, agentRunService, agentRunTelemetryService, eventMapper,
                harnessRuntime, harnessCollaborationService, new HarnessSubagentEventRelay());
    }

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            HarnessEventMapper eventMapper
    ) {
        this(chatSessionService, agentRunService, agentRunTelemetryService, eventMapper,
                new AgentScopeHarnessRuntime(), null, new HarnessSubagentEventRelay());
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.HARNESS_STREAMING;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
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
            reactor.core.publisher.Flux<AgentEvent> events = command.gatewaySubagentId() == null
                    ? harnessRuntime.streamParent(command.agent().agentBean(), command.userMessage(), runtimeContext)
                    : harnessRuntime.streamSubagent(
                            command.agent().agentBean(),
                            new HarnessSubagentContext(
                                    command.subagentAgentId(),
                                    String.valueOf(command.userId()),
                                    command.subagentParentSessionId(),
                                    command.sessionId(),
                                    command.subagentAssignment(),
                                    command.subagentExecutionId()
                            ),
                            command.userMessage()
                    );
            Disposable subscription = events
                    .subscribe(execution::onEvent, execution::onError, execution::onComplete);
            execution.attachSubscription(subscription);
            if (command.sink().isCancelled()) {
                execution.cancel("客户端已断开");
            }
        } catch (RuntimeException ex) {
            execution.onError(ex);
        }
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
        private final Map<String, String> agentSessionIdBySource = new HashMap<>();
        // 同一个 agentId 可以被并行调用多次，source 只标识 agent 路径而不标识具体调用。
        // replyId 才是一次调用的唯一关联键，用它把 START / RESULT / END 归入正确子会话。
        private final Map<String, String> agentSessionIdByReply = new HashMap<>();
        // AgentScope 保证成功执行时 AGENT_RESULT 紧邻并先于 AGENT_END；结果先暂存，END 才提交完成状态。
        private final Map<String, String> childResultByReply = new HashMap<>();
        private final Map<String, Runnable> relayUnsubscribers = new ConcurrentHashMap<>();
        // 同一条子事件可能同时经 AgentScope 父 emitter 与产品 relay 到达。SDK eventId 是
        // 原始事件的稳定身份；按它去重，避免父时间线双发，同时保留 relay 的后台续传能力。
        private final Set<String> observedEventIds = new HashSet<>();

        private Execution(ChatAgentExecutionCommand command) {
            this.command = command;
            agentSessionIdBySource.put("", command.sessionId());
        }

        private synchronized void onEvent(AgentEvent event) {
            if (responseTerminal.get() || command.sink().isCancelled()) {
                return;
            }
            if (hasText(event.getId()) && !observedEventIds.add(event.getId())) {
                return;
            }
            String eventAgentSessionId = resolveEventAgentSessionId(event);
            String exposedParentSessionId = null;
            String exposedChildSessionId = null;
            HarnessSubagentSummaryDto projectedSubagent = null;
            try {
                if (event instanceof SubagentExposedEvent exposed
                        && harnessCollaborationService != null) {
                    String label = exposed.getLabel() == null || exposed.getLabel().isBlank()
                            ? exposed.getAgentId()
                            : exposed.getLabel();
                    // SUBAGENT_EXPOSED 不携带 task，且相同 agentId 的工具调用可以并行，不能
                    // 通过 FIFO 猜测归属。先用 label 建立 child session；子 Agent 的 onAgent
                    // 生命周期随后会按精确 sessionId 投影真实委托。
                    String assignment = label;
                    String parentSessionId = agentSessionIdBySource.get(normalizeSource(event.getSource()));
                    if (parentSessionId == null) {
                        // Gateway 子流以目标子 Agent 为根；事件未提供 source 时仍能正确挂到目标节点。
                        parentSessionId = command.sessionId();
                    }
                    exposedParentSessionId = parentSessionId;
                    projectedSubagent = harnessCollaborationService.exposeSubagent(
                            command.userId(),
                            command.rootSessionId(),
                            new HarnessSubagentExposure(
                                    exposed.getSubagentId(),
                                    exposed.getAgentId(),
                                    parentSessionId,
                                    exposed.getSessionId(),
                                    label,
                                    assignment
                            )
                    );
                    exposedChildSessionId = exposed.getSessionId();
                }
                if (event instanceof AgentStartEvent start
                        && !isParent(event)
                        && harnessCollaborationService != null
                        && hasText(start.getReplyId())
                        && hasText(eventAgentSessionId)) {
                    agentSessionIdBySource.put(normalizeSource(event.getSource()), eventAgentSessionId);
                    agentSessionIdByReply.put(start.getReplyId(), eventAgentSessionId);
                    var running = harnessCollaborationService.markRunning(
                            command.userId(), command.rootSessionId(), eventAgentSessionId, start.getReplyId()
                    );
                    if (running != null) {
                        projectedSubagent = running;
                    }
                }
                if (event instanceof AgentResultEvent childResult
                        && !isParent(event)
                        && harnessCollaborationService != null) {
                    Msg result = childResult.getResult();
                    if (result != null && hasText(result.getId()) && hasText(result.getTextContent())) {
                        childResultByReply.put(result.getId(), result.getTextContent());
                    }
                }
                if (event instanceof AgentEndEvent childEnd
                        && !isParent(event)
                        && harnessCollaborationService != null) {
                    String replyId = childEnd.getReplyId();
                    String childSessionId = hasText(replyId)
                            ? agentSessionIdByReply.remove(replyId)
                            : null;
                    String result = hasText(replyId) ? childResultByReply.remove(replyId) : null;
                    if (hasText(childSessionId) && hasText(result)) {
                        projectedSubagent = harnessCollaborationService.completeSubagent(
                                command.userId(),
                                command.rootSessionId(),
                                childSessionId,
                                replyId,
                                result
                        ).subagent();
                    } else if (hasText(childSessionId)) {
                        // SDK 的成功事件序列应包含 AGENT_RESULT；缺失结果时不能把协作者误报为已完成。
                        projectedSubagent = harnessCollaborationService.failSubagent(
                                command.userId(), command.rootSessionId(), childSessionId, replyId,
                                HarnessSubagentFailureReason.PROTOCOL_INCOMPLETE,
                                "AGENT_END arrived without AGENT_RESULT"
                        );
                    }
                    // AgentSpawnTool 还会额外发送 replyId=null 的外层包装 END。它只表示
                    // spawn 工具调用收尾，不是另一次子 Agent 失败终态。
                }
            } catch (RuntimeException projectionError) {
                // 协作投影属于父执行的局部结果；单个子 Agent 状态冲突或持久化失败不能打断父回复。
                log.warn(
                        "[HarnessExecutor] 协作 Agent 生命周期投影失败，父执行继续 sessionId={}, eventType={}, error={}",
                        command.sessionId(), event.getType(), projectionError.getMessage(), projectionError
                );
            }
            // sequence 按原始 AgentEvent 流严格递增；每条事件都通过同一 SSE 信封向前端发送。
            emit(eventMapper.map(
                    command.runHandle().id(),
                    sequence.incrementAndGet(),
                    event,
                    exposedParentSessionId,
                    eventAgentSessionId,
                    projectedSubagent
            ));
            if (exposedChildSessionId != null) {
                String childSessionId = exposedChildSessionId;
                relayUnsubscribers.computeIfAbsent(childSessionId, ignored ->
                        subagentEventRelay.subscribe(
                                String.valueOf(command.userId()),
                                childSessionId,
                                childEvent -> onRelayedSubagentEvent(childSessionId, childEvent.event())
                        )
                );
            }

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

        private String normalizeSource(String source) {
            return source == null ? "" : source;
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        private String resolveEventAgentSessionId(AgentEvent event) {
            if (event instanceof SubagentExposedEvent exposed) {
                return exposed.getSessionId();
            }
            if (isParent(event)) {
                return null;
            }
            if (event instanceof AgentStartEvent start) {
                if (hasText(start.getSessionId())) {
                    return start.getSessionId();
                }
            }
            if (event instanceof TextBlockDeltaEvent delta) {
                String byReply = agentSessionIdByReply.get(delta.getReplyId());
                if (hasText(byReply)) {
                    return byReply;
                }
            } else if (event instanceof ThinkingBlockDeltaEvent delta) {
                String byReply = agentSessionIdByReply.get(delta.getReplyId());
                if (hasText(byReply)) {
                    return byReply;
                }
            } else if (event instanceof AgentResultEvent resultEvent) {
                Msg result = resultEvent.getResult();
                String byReply = result == null ? null : agentSessionIdByReply.get(result.getId());
                if (hasText(byReply)) {
                    return byReply;
                }
            } else if (event instanceof AgentEndEvent endEvent) {
                String byReply = agentSessionIdByReply.get(endEvent.getReplyId());
                if (hasText(byReply)) {
                    return byReply;
                }
            }
            return agentSessionIdBySource.get(normalizeSource(event.getSource()));
        }

        private void onRelayedSubagentEvent(String childSessionId, AgentEvent event) {
            String relaySource = "product-relay/" + childSessionId;
            synchronized (this) {
                agentSessionIdBySource.put(relaySource, childSessionId);
            }
            onEvent(event.withSource(relaySource));
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
                if (command.gatewaySubagentId() == null && !reasoning.isBlank()) {
                    chatSessionService.appendReasoningMessage(
                            command.userId(),
                            command.sessionId(),
                            reasoning
                    );
                }
                Long assistantMessageId;
                ChatSessionMessageDto assistantMessage = null;
                if (command.gatewaySubagentId() == null) {
                    assistantMessageId = chatSessionService.appendAssistantMessage(
                            command.userId(), command.rootSessionId(), reply
                    );
                    assistantMessage = chatSessionService.getOwnedMessage(
                            command.userId(), command.rootSessionId(), assistantMessageId
                    );
                } else {
                    HarnessSubagentCompletion completion = harnessCollaborationService.completeSubagent(
                            command.userId(),
                            command.rootSessionId(),
                            command.sessionId(),
                            command.subagentExecutionId(),
                            reply
                    );
                    assistantMessageId = completion.assistantMessageId();
                }
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
                if (command.gatewaySubagentId() != null && harnessCollaborationService != null) {
                    harnessCollaborationService.failSubagent(
                            command.userId(),
                            command.rootSessionId(),
                            command.sessionId(),
                            command.subagentExecutionId(),
                            HarnessSubagentFailureReason.EXECUTION_ERROR,
                            error.getMessage()
                    );
                }
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
                if (command.gatewaySubagentId() != null && harnessCollaborationService != null) {
                    // 当前运行记录按失败收尾；同步回收产品状态，避免断流后协作者永久停在 RUNNING。
                    harnessCollaborationService.failSubagent(
                            command.userId(), command.rootSessionId(), command.sessionId(),
                            command.subagentExecutionId(),
                            HarnessSubagentFailureReason.CANCELLED,
                            reason
                    );
                }
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
                relayUnsubscribers.values().forEach(Runnable::run);
                relayUnsubscribers.clear();
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

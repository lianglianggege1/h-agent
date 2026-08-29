package com.h.backend.chat.domain.agent;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.NoopAgentObservability;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ExecutionObservationCarrier;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessSubagentCompletion;
import com.h.backend.chat.application.HarnessSubagentExposure;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.application.ApprovalRequestService;
import com.h.backend.chat.domain.approval.ApprovalEpisode;
import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionCatalog;
import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import com.h.backend.chat.infrastructure.config.SubagentCatalogProperties;
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
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.FluxSink;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final HarnessEventMapper eventMapper;
    private final HarnessRuntime harnessRuntime;
    private final HarnessCollaborationService harnessCollaborationService;
    private final HarnessSubagentEventRelay subagentEventRelay;
    private final ObjectProvider<SubagentDefinitionCatalog> subagentCatalogProvider;
    private final ObjectProvider<SubagentCatalogProperties> subagentCatalogPropertiesProvider;
    private final AgentObservability observability;
    private final ApprovalRequestService approvalRequestService;
    private final AgentScopeApprovalAdapter approvalAdapter;

    @Autowired
    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            HarnessEventMapper eventMapper,
            HarnessRuntime harnessRuntime,
            HarnessCollaborationService harnessCollaborationService,
            HarnessSubagentEventRelay subagentEventRelay,
            ObjectProvider<SubagentDefinitionCatalog> subagentCatalogProvider,
            ObjectProvider<SubagentCatalogProperties> subagentCatalogPropertiesProvider,
            ObjectProvider<AgentObservability> observabilityProvider,
            ApprovalRequestService approvalRequestService,
            AgentScopeApprovalAdapter approvalAdapter
    ) {
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.eventMapper = eventMapper;
        this.harnessRuntime = harnessRuntime;
        this.harnessCollaborationService = harnessCollaborationService;
        this.subagentEventRelay = subagentEventRelay;
        this.subagentCatalogProvider = subagentCatalogProvider;
        this.subagentCatalogPropertiesProvider = subagentCatalogPropertiesProvider;
        this.observability = observabilityProvider != null
                ? observabilityProvider.getIfAvailable(NoopAgentObservability::getInstance)
                : NoopAgentObservability.getInstance();
        this.approvalRequestService = approvalRequestService;
        this.approvalAdapter = approvalAdapter;
    }

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            HarnessEventMapper eventMapper,
            HarnessRuntime harnessRuntime,
            HarnessCollaborationService harnessCollaborationService
    ) {
        this(chatSessionService, agentRunService, eventMapper,
                harnessRuntime, harnessCollaborationService, new HarnessSubagentEventRelay(),
                null, null, null, null, null);
    }

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            HarnessEventMapper eventMapper,
            HarnessRuntime harnessRuntime,
            HarnessCollaborationService harnessCollaborationService,
            HarnessSubagentEventRelay subagentEventRelay
    ) {
        this(chatSessionService, agentRunService, eventMapper,
                harnessRuntime, harnessCollaborationService, subagentEventRelay,
                null, null, null, null, null);
    }

    public HarnessAgentExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            HarnessEventMapper eventMapper
    ) {
        this(chatSessionService, agentRunService, eventMapper,
                new AgentScopeHarnessRuntime(null, null), null, new HarnessSubagentEventRelay(),
                null, null, null, null, null);
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.HARNESS_STREAMING;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        log.info("[HarnessExecutor] Agent执行开始 userId={}, sessionId={}, runId={}",
                command.userId(), command.sessionId(), command.runHandle().id());
        boolean parentTurn = command.gatewaySubagentId() == null;
        // 父 turn 开始时生成不可变 Catalog 快照（设计 7.1）：定义在 turn 中途发布或停用
        // 不影响本轮执行；Execution 持有同一 snapshot 用于 exposure 版本固定。
        SubagentTurnSnapshot snapshot = parentTurn ? snapshotForTurn(command.userId()) : null;
        // 观测阶段载体（设计 7.3）：PRIMARY 在产品结果提交后原子结束，之后的后置工作
        // （记忆提取/整理/Hook）延迟创建并进入 Maintenance trace。
        ExecutionObservationCarrier carrier = new ExecutionObservationCarrier(
                observability, command.observation(), maintenanceStart(command));
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .userId(String.valueOf(command.userId()))
                .sessionId(command.sessionId())
                // Harness 2.0.1 只有显式打开该上下文标记才会发 SUBAGENT_EXPOSED。
                .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
                // 类型化观测阶段载体（设计 7.3 / 12.4）：观测 middleware 与 SDK 子 Agent
                // 派生上下文据此挂到本轮 trace，并在响应提交后切换 Maintenance。
                .put(ExecutionObservationCarrier.class, carrier);
        if (snapshot != null) {
            contextBuilder.put(SubagentTurnSnapshot.class, snapshot);
        }
        RuntimeContext runtimeContext = contextBuilder.build();
        Execution execution = new Execution(command, snapshot, carrier);

        command.sink().onCancel(() -> execution.cancel("客户端已断开"));
        try {
            reactor.core.publisher.Flux<AgentEvent> events = parentTurn
                    ? harnessRuntime.streamParent(
                            command.agent().agentBean(),
                            command.userMessage(),
                            runtimeContext,
                            command.approvalMode()
                    )
                    : harnessRuntime.streamSubagent(
                            command.agent().agentBean(),
                            new HarnessSubagentContext(
                                    command.subagentAgentId(),
                                    String.valueOf(command.userId()),
                                    command.subagentParentSessionId(),
                                    command.sessionId(),
                                    command.subagentAssignment(),
                                    command.subagentExecutionId(),
                                    command.subagentDefinitionBinding()
                            ),
                            command.userMessage(),
                            carrier,
                            command.approvalMode()
                    );
            subscribe(command, execution, events, carrier);
        } catch (RuntimeException ex) {
            execution.onError(ex);
        }
    }

    /** 继续同一个 run；确认消息由运行时从 AgentState 的 ASKING 工具重建。 */
    public void resumeApproval(
            ChatAgentExecutionCommand command,
            java.util.List<String> toolCallIds,
            boolean approved
    ) {
        log.info("[HarnessExecutor] Agent审批后恢复 userId={}, sessionId={}, runId={}, approved={}",
                command.userId(), command.sessionId(), command.runHandle().id(), approved);
        boolean parentTurn = command.gatewaySubagentId() == null;
        ExecutionObservationCarrier carrier = carrierFor(command);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(command.userId()))
                .sessionId(command.sessionId())
                .put(AgentSpawnTool.CTX_EXPOSE_TO_USER, true)
                .put(ExecutionObservationCarrier.class, carrier)
                .build();
        Execution execution = new Execution(command, null, carrier);
        command.sink().onCancel(() -> execution.cancel("客户端已断开"));
        try {
            reactor.core.publisher.Flux<AgentEvent> events = parentTurn
                    ? harnessRuntime.resumeParent(
                            command.agent().agentBean(), runtimeContext, toolCallIds, approved
                    )
                    : harnessRuntime.resumeSubagent(
                            command.agent().agentBean(),
                            new HarnessSubagentContext(
                                    command.subagentAgentId(), String.valueOf(command.userId()),
                                    command.subagentParentSessionId(), command.sessionId(),
                                    command.subagentAssignment(), command.subagentExecutionId(),
                                    command.subagentDefinitionBinding()
                            ),
                            toolCallIds,
                            approved,
                            carrier
                    );
            subscribe(command, execution, events, carrier);
        } catch (RuntimeException error) {
            execution.onError(error);
        }
    }

    private void subscribe(
            ChatAgentExecutionCommand command,
            Execution execution,
            reactor.core.publisher.Flux<AgentEvent> events,
            ExecutionObservationCarrier carrier
    ) {
        Disposable subscription = events
                .doOnComplete(carrier::executionCompleted)
                .doOnError(carrier::executionFailed)
                .doOnCancel(carrier::executionCancelled)
                .subscribe(execution::onEvent, execution::onError, execution::onComplete);
        execution.attachSubscription(subscription);
        if (command.sink().isCancelled()) {
            execution.cancel("客户端已断开");
        }
    }

    private ExecutionObservationCarrier carrierFor(ChatAgentExecutionCommand command) {
        return new ExecutionObservationCarrier(
                observability, command.observation(), maintenanceStart(command));
    }

    /**
     * Maintenance trace 的启动元数据（设计 7.3 规则 4）：沿用产品 Session、rootRunId
     * 与环境标签，只把 entry_kind 标为 maintenance。
     */
    private static AgentExecutionStart maintenanceStart(ChatAgentExecutionCommand command) {
        return new AgentExecutionStart(
                "agent.maintenance",
                command.rootSessionId(),
                command.userId(),
                command.agent().agentId(),
                command.sessionId(),
                "maintenance",
                String.valueOf(command.runHandle().id()),
                List.of(),
                Map.of(),
                null
        );
    }

    /**
     * Catalog 关闭或实现不可用时返回 null，父 turn 完全保持 SDK 静态行为。
     */
    private SubagentTurnSnapshot snapshotForTurn(Long userId) {
        if (subagentCatalogProvider == null || subagentCatalogPropertiesProvider == null) {
            return null;
        }
        SubagentCatalogProperties properties = subagentCatalogPropertiesProvider.getIfAvailable();
        SubagentDefinitionCatalog catalog = subagentCatalogProvider.getIfAvailable();
        if (properties == null || !properties.isEnabled() || catalog == null || userId == null) {
            return null;
        }
        try {
            return catalog.snapshotForTurn(userId);
        } catch (RuntimeException error) {
            // 快照失败时让本次 turn 以静态内置继续，产品聊天不能因为 Catalog 故障整体不可用。
            log.warn("[HarnessExecutor] 生成 Subagent turn snapshot 失败，降级为静态内置 userId={}: {}",
                    userId, error.getMessage(), error);
            return null;
        }
    }

    private final class Execution {

        private final ChatAgentExecutionCommand command;
        /** 父 turn 的不可变 Catalog 快照；exposure 用它把 agent_id 固定到 Definition Version。 */
        private final SubagentTurnSnapshot snapshot;
        /** 观测阶段载体（设计 7.3）：Primary 终态与 Maintenance 生命周期都经由它协调。 */
        private final ExecutionObservationCarrier carrier;
        private final AtomicLong sequence = new AtomicLong();
        // 用户可见响应与 Harness 后置维护是两个终态；父响应结束后仍允许记忆任务继续运行。
        private final AtomicBoolean responseTerminal = new AtomicBoolean();
        private final AtomicBoolean executionReleased = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean emittedParentText = new AtomicBoolean();
        private final AtomicReference<Msg> parentResult = new AtomicReference<>();
        private final AtomicReference<ApprovalEpisode> pendingApproval = new AtomicReference<>();
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

        private Execution(
                ChatAgentExecutionCommand command,
                SubagentTurnSnapshot snapshot,
                ExecutionObservationCarrier carrier
        ) {
            this.command = command;
            this.snapshot = snapshot;
            this.carrier = carrier;
            agentSessionIdBySource.put("", command.sessionId());
        }

        /**
         * 从本 turn snapshot 解析 exposure 的版本绑定（设计 7.5）。
         * 禁止在 exposure 时查询"当前发布版本"；不在 snapshot 内的 agent（SDK 合成
         * general-purpose、未入库的静态声明）按无绑定处理，保持既有会话语义。
         */
        private DefinitionBinding bindingOf(String agentId) {
            if (snapshot == null || agentId == null) {
                return null;
            }
            ResolvedSubagentDefinition resolved = snapshot.resolve(agentId);
            return resolved == null ? null : resolved.binding();
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
                                    assignment,
                                    bindingOf(exposed.getAgentId())
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
            } else if (isParent(event) && event instanceof RequireUserConfirmEvent confirmEvent
                    && approvalAdapter != null) {
                pendingApproval.compareAndSet(null, approvalAdapter.capture(confirmEvent));
            } else if (isParent(event) && event instanceof AgentEndEvent) {
                completeResponse();
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
            completeResponse();
        }

        private void completeResponse() {
            if (pendingApproval.get() != null) {
                suspendForApproval();
            } else {
                completeSuccessfulResponse();
            }
        }

        private void suspendForApproval() {
            if (!responseTerminal.compareAndSet(false, true)) {
                return;
            }
            try {
                if (approvalRequestService == null || command.approvalMode() == null) {
                    throw new IllegalStateException("HITL approval service is unavailable");
                }
                var request = approvalRequestService.suspend(
                        new ApprovalRequestService.SuspendApprovalCommand(
                                command.runHandle().id(),
                                command.userId(),
                                command.rootSessionId(),
                                command.sessionId(),
                                command.subagentExecutionId(),
                                command.approvalMode(),
                                pendingApproval.get()
                        )
                );
                // 审批点结束当前 Primary observation；恢复阶段使用持久化的 traceparent
                // 在同一 trace 下创建新的 Primary observation。
                carrier.completePrimary(null);
                releaseExecution();
                emit(new ChatStreamEvent("action_required", "", null, request));
                completeSink();
                log.info("[HarnessExecutor] Agent等待人工审批 userId={}, sessionId={}, runId={}, approvalId={}",
                        command.userId(), command.sessionId(), command.runHandle().id(), request.approvalId());
            } catch (RuntimeException error) {
                failAfterResponseClaim(error, true);
            } finally {
                releaseExecution();
            }
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
                // 产品结果与 Run 已提交：原子结束 Primary 并切入 MAINTENANCE（设计 7.3 规则 1）。
                carrier.completePrimary(SemanticContent.ofMessages(
                        List.of(SemanticMessage.of("assistant", reply))));
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
                agentRunService.failRun(command.runHandle().id(), reason);
                carrier.cancelPrimary(reason);
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
            carrier.failPrimary(error);
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

package com.h.backend.chat.application.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.semantic.ArtifactReferenceBlock;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.agent.observability.semantic.ContentCaptureState;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.domain.agent.ChatAgentExecutor;
import com.h.backend.chat.domain.agent.HAssistantStreamingExecutor;
import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamConcurrencyGuard;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessExecutionSession;
import com.h.backend.chat.application.HarnessSubagentTurnStart;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.observability.BusinessArtifactReferenceMapper;
import com.h.backend.skill.application.SkillRuntimeService;
import com.h.backend.skill.domain.SkillPlatformException;
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
    private final AgentObservability observability;
    private final ExecutorService chatStreamExecutor;
    private final ChatStreamConcurrencyGuard concurrencyGuard;
    private final ImageGenerationService imageGenerationService;
    private final AgentRegistry agentRegistry;
    private final ChatMemoryIdFactory chatMemoryIdFactory;
    private final HarnessCollaborationService harnessCollaborationService;
    private final Map<AgentRuntimeType, ChatAgentExecutor> executors;
    /** Skill 快照固定点；为 null 时本次部署未启用 Skill Runtime。 */
    private final SkillRuntimeService skillRuntimeService;

    @Autowired
    public ChatServiceImpl(
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            AgentRegistry agentRegistry,
            ChatMemoryIdFactory chatMemoryIdFactory,
            HarnessCollaborationService harnessCollaborationService,
            List<ChatAgentExecutor> executors,
            SkillRuntimeService skillRuntimeService
    ) {
        this.systemPromptService = systemPromptService;
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.observability = observability;
        this.chatStreamExecutor = chatStreamExecutor;
        this.concurrencyGuard = concurrencyGuard;
        this.imageGenerationService = imageGenerationService;
        this.agentRegistry = agentRegistry;
        this.chatMemoryIdFactory = chatMemoryIdFactory;
        this.harnessCollaborationService = harnessCollaborationService;
        this.executors = toExecutorMap(executors);
        this.skillRuntimeService = skillRuntimeService;
    }

    public ChatServiceImpl(
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            AgentRegistry agentRegistry,
            ChatMemoryIdFactory chatMemoryIdFactory,
            List<ChatAgentExecutor> executors
    ) {
        this(systemPromptService, chatSessionService, agentRunService, observability,
                chatStreamExecutor, concurrencyGuard, imageGenerationService, agentRegistry,
                chatMemoryIdFactory, null, executors, null);
    }

    public ChatServiceImpl(
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService,
            AgentRegistry agentRegistry,
            ChatMemoryIdFactory chatMemoryIdFactory,
            HarnessCollaborationService harnessCollaborationService,
            List<ChatAgentExecutor> executors
    ) {
        this(systemPromptService, chatSessionService, agentRunService, observability,
                chatStreamExecutor, concurrencyGuard, imageGenerationService, agentRegistry,
                chatMemoryIdFactory, harnessCollaborationService, executors, null);
    }

    public ChatServiceImpl(
            HAssistant hAssistant,
            SystemPromptService systemPromptService,
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard
    ) {
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
            AgentObservability observability,
            ExecutorService chatStreamExecutor,
            ChatStreamConcurrencyGuard concurrencyGuard,
            ImageGenerationService imageGenerationService
    ) {
        this(
                hAssistant,
                systemPromptService,
                chatSessionService,
                agentRunService,
                observability,
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
            AgentObservability observability,
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
                observability,
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
            AgentObservability observability,
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
                observability,
                chatStreamExecutor,
                concurrencyGuard,
                imageGenerationService,
                agentRegistry,
                new ChatMemoryIdFactory(),
                withStandardExecutorIfMissing(
                        hAssistant,
                        chatSessionService,
                        agentRunService,
                        chatStreamEventBridge,
                        new com.h.backend.memory.application.SuccessfulTurnCommitter(
                                chatSessionService,
                                agentRunService,
                                new com.h.backend.memory.application.NoopLongTermMemoryRuntime(),
                                new com.h.backend.memory.domain.AgentMemoryPolicyCatalog()
                        ),
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
            String userMessage,
            List<ChatMessageResourceUseDto> resources
    ) {
        return Flux.defer(() -> {
            if (isStandardImageCommand(agentId, userMessage)) {
                return Flux.create(sink -> {
                    try {
                        chatStreamExecutor.submit(() ->
                                runImageCommandStream(sink, userId, promptId, sessionId, userMessage, resources));
                    } catch (RuntimeException ex) {
                        log.error("Failed to submit image command stream task", ex);
                        emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务调用失败"));
                    }
                });
            }
            final ExecutionAddress address;
            try {
                address = resolveExecutionAddress(userId, agentId, sessionId);
            } catch (RuntimeException ex) {
                String publicMessage = ex instanceof BusinessException ? ex.getMessage() : "AI 服务调用失败";
                return Flux.just(new ChatStreamEvent("error", publicMessage));
            }
            // 并发互斥的粒度是实际 Agent Session：同一子 Agent 串行，父子及兄弟可并行。
            ChatStreamConcurrencyGuard.Permit permit = concurrencyGuard.tryAcquire(address.sessionId(), userId);
            if (!permit.acquired()) {
                return Flux.just(new ChatStreamEvent("error", permit.message()));
            }
            return Flux.create(sink -> {
                try {
                    chatStreamExecutor.submit(() ->
                            runChatStream(sink, permit, userId, promptId, agentId, address,
                                    userMessage, resources));
                } catch (RuntimeException ex) {
                    log.error("Failed to submit chat stream task", ex);
                    permit.release();
                    emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "AI 服务调用失败"));
                }
            });
        });
    }

    private void runImageCommandStream(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            Long promptId,
            String sessionId,
            String userMessage,
            List<ChatMessageResourceUseDto> resources
    ) {
        try {
            chatSessionService.assertActiveSession(
                    userId,
                    sessionId,
                    promptId,
                    AgentRegistry.STANDARD_CHAT_AGENT_ID
            );
            Long resolvedPromptId = systemPromptService.resolvePromptId(userId, promptId);
            emitImageCommandEvents(sink, userId, resolvedPromptId, sessionId, userMessage, resources);
        } catch (Exception ex) {
            log.error("Error preparing image command stream", ex);
            String publicMessage = ex instanceof BusinessException
                    ? ex.getMessage()
                    : "AI 服务调用失败";
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", publicMessage));
        }
    }

    /**
     * 在工作线程中完成一次聊天流的准备工作，再将实际生成交给对应 runtime 的 executor。
     *
     * <p>这里的 {@code address.sessionId} 始终是本次实际执行的 Agent Session：父 Harness
     * 请求等于顶级会话；子 Agent 请求则是子 Agent 的独立会话。因此并发锁、{@code agent_runs}、
     * 消息顺序和流式连接都按它隔离。{@code rootSessionId} 只用于校验该会话属于用户的同一顶级
     * Harness 会话，以及在需要时回写父会话的活跃时间。</p>
     *
     * <p>permit 的释放责任会移交给 executor：正常流结束、取消或 executor 内部失败时均由其回调释放。
     * 本方法只处理 executor 尚未接管前的失败；{@code permitReleased} 防止 image 快捷路径、异常补偿
     * 和 executor 回调竞争时重复释放同一组 Redis permit。</p>
     */
    private void runChatStream(
            FluxSink<ChatStreamEvent> sink,
            ChatStreamConcurrencyGuard.Permit permit,
            Long userId,
            Long promptId,
            String agentId,
            ExecutionAddress address,
            String userMessage,
            List<ChatMessageResourceUseDto> resources
    ) {
        // permit 由当前准备阶段或后续 executor 任一方释放，但整个请求只能释放一次。
        AtomicBoolean permitReleased = new AtomicBoolean();
        AgentExecutionObservation observation = null;
        AgentRunService.AgentRunHandle runHandle = null;
        boolean subagentTurnStarted = false;
        String subagentExecutionId = null;
        try {
            AgentDefinition agent = resolveAgent(agentId);
            boolean standardChat = agent.runtimeType() == AgentRuntimeType.STANDARD_STREAMING_CHAT;
            // Prompt 只属于标准聊天；Harness/领域 Agent 固定由其 agent 定义驱动，不能借用标准 Prompt 校验。
            Long promptIdForSessionValidation = standardChat ? promptId : null;
            // 即使请求的是子 Agent，也必须先验证顶级产品会话的所有权和 agent 绑定。
            chatSessionService.assertActiveSession(
                    userId,
                    address.rootSessionId(),
                    promptIdForSessionValidation,
                    agent.agentId()
            );
            if (agentRunService.hasOpenRun(address.sessionId())) {
                throw new BusinessException(40901, "当前会话仍有运行中或待审批任务，请先处理后再发送消息");
            }

            Long resolvedPromptId = standardChat ? systemPromptService.resolvePromptId(userId, promptId) : null;
            if (standardChat && isImageCommand(userMessage)) {
                // 图片命令不会交给通用文本 executor；该分支在这里终止，故必须自行归还 permit。
                emitImageCommandEvents(sink, userId, resolvedPromptId, address.rootSessionId(), userMessage, resources);
                releasePermitOnce(permit, permitReleased);
                return;
            }

            Long userMessageId;
            if (!address.subagent()) {
                // 顶级 Agent 的用户消息写入顶级聊天会话。
                userMessageId = chatSessionService.appendUserMessage(
                        userId, address.rootSessionId(), userMessage, resources
                );
            } else {
                if (harnessCollaborationService == null) {
                    throw new IllegalStateException("HarnessCollaborationService is required for subagent turns");
                }
                // 这是一个原子操作：写入子会话用户消息、绑定资源、将该子 Agent 改为 RUNNING。
                // 同一子 Agent 的第二个请求会在前面的 session 级 permit 处被拒绝，不会产生并行 turn。
                HarnessSubagentTurnStart turn = harnessCollaborationService.beginSubagentTurn(
                        userId, address.rootSessionId(), address.sessionId(), userMessage, resources
                );
                subagentTurnStarted = true;
                userMessageId = turn.userMessageId();
                subagentExecutionId = turn.executionId();
            }
            // 统一回读已落库消息，确保 SSE 给前端的 id、附件和 payload 与历史查询完全一致。
            ChatSessionMessageDto persistedUserMessage =
                    chatSessionService.getOwnedMessage(userId, address.rootSessionId(), userMessageId);
            emitIfActive(sink, new ChatStreamEvent("user_message", "", persistedUserMessage));
            // run 和观测都归属实际执行会话，不能写成 rootSessionId，否则无法区分各子 Agent 的运行记录。
            // 固定顺序（设计 7.1）：先创建 trace_id 为空的 Run，再开始 Observation，最后尽力回写 trace ID。
            runHandle = agentRunService.createRun(
                    address.sessionId(),
                    userId,
                    resolvedPromptId,
                    userMessageId,
                    agent.agentId(),
                    null
            );
            String memoryId = buildMemoryId(userId, resolvedPromptId, agent.agentId(), address.sessionId());
            // 顶层 Agent 请求开始时固定 Skill 快照（设计 §14.1）：Subagent 不自动加载；
            // 任一必需 Artifact 失败时在 model 调用前明确失败，不静默少加载。
            if (skillRuntimeService != null && !address.subagent()) {
                skillRuntimeService.snapshotForTopLevelRun(userId, runHandle.id(), memoryId);
            }
            observation = observability.start(new AgentExecutionStart(
                    "agent.run",
                    address.rootSessionId(),
                    userId,
                    agent.agentId(),
                    address.sessionId(),
                    address.subagent() ? "subagent" : "chat",
                    String.valueOf(runHandle.id()),
                    List.of(),
                    Map.of(),
                    agentInputContent(persistedUserMessage, userMessage)
            ));
            try {
                String traceId = observation.traceId();
                if (traceId != null) {
                    agentRunService.updateTraceId(runHandle.id(), traceId);
                }
                Map<String, String> propagationHeaders = new java.util.HashMap<>();
                observability.inject(observation.observationContext(), propagationHeaders);
                agentRunService.bindApprovalContext(
                        runHandle.id(), address.approvalMode(), propagationHeaders.get("traceparent")
                );
            } catch (RuntimeException traceWriteError) {
                // 回写失败只记录受限日志；Agent 继续执行，观测不改变业务行为。
                log.warn("Failed to write trace id back to agent run runId={}: {}",
                        runHandle.id(), traceWriteError.getMessage());
            }
            // executor 接收 root 与实际 session 两个 id：前者用于 Harness 树投影，后者用于 Gateway 子 Agent 寻址与消息落库。
            ChatAgentExecutor executor = executorFor(agent.runtimeType());
            executor.execute(new ChatAgentExecutionCommand(
                    sink,
                    userId,
                    resolvedPromptId,
                    address.sessionId(),
                    address.rootSessionId(),
                    address.gatewaySubagentId(),
                    address.subagentAgentId(),
                    address.subagentParentSessionId(),
                    address.subagentAssignment(),
                    subagentExecutionId,
                    address.subagentDefinitionBinding(),
                    userMessage,
                    resources,
                    memoryId,
                    agent,
                    runHandle,
                    observation,
                    address.approvalMode(),
                    () -> releasePermitOnce(permit, permitReleased),
                    userMessageId
            ));
        } catch (Exception ex) {
            try {
                log.error("Error preparing chat stream", ex);
                if (subagentTurnStarted && address.subagent() && harnessCollaborationService != null) {
                    try {
                        // beginSubagentTurn 已将状态改为 RUNNING；executor 尚未接管就失败时必须补偿，避免永久假运行。
                        harnessCollaborationService.failSubagent(
                                userId,
                                address.rootSessionId(),
                                address.sessionId(),
                                subagentExecutionId,
                                HarnessSubagentFailureReason.PREPARATION_ERROR,
                                ex.getMessage()
                        );
                    } catch (RuntimeException compensationError) {
                        log.warn("Failed to mark subagent turn as failed sessionId={}",
                                address.sessionId(), compensationError);
                    }
                }
                // run 可能尚未创建成功；按已经拿到的资源分别收尾，避免错误处理掩盖原异常。
                if (runHandle != null) {
                    agentRunService.failRun(
                            runHandle.id(),
                            ex.getMessage() == null ? "AI 服务调用失败" : ex.getMessage()
                    );
                }
                if (observation != null) {
                    observation.fail(ex);
                }
                String publicMessage = ex instanceof BusinessException || ex instanceof SkillPlatformException
                        ? ex.getMessage()
                        : "AI 服务调用失败";
                emitAndCompleteIfActive(sink, new ChatStreamEvent("error", publicMessage));
            } finally {
                releasePermitOnce(permit, permitReleased);
            }
        }
    }

    private ExecutionAddress resolveExecutionAddress(
            Long userId,
            String agentId,
            String sessionId
    ) {
        AgentDefinition agent = resolveAgent(agentId);
        if (agent.runtimeType() != AgentRuntimeType.HARNESS_STREAMING) {
            return new ExecutionAddress(sessionId, sessionId, null, null, null, null, null, null);
        }
        if (harnessCollaborationService == null) {
            throw new IllegalStateException("HarnessCollaborationService is required for subagent turns");
        }
        HarnessExecutionSession resolved = harnessCollaborationService.resolveExecutionSession(userId, sessionId);
        return new ExecutionAddress(
                resolved.rootSessionId(),
                resolved.sessionId(),
                resolved.gatewaySubagentId(),
                resolved.subagentAgentId(),
                resolved.parentSessionId(),
                resolved.assignment(),
                resolved.definitionBinding(),
                resolved.approvalMode()
        );
    }

    /** HTTP 只传实际 sessionId；根归属和 Gateway 句柄是后端派生事实。 */
    private record ExecutionAddress(
            String rootSessionId,
            String sessionId,
            String gatewaySubagentId,
            String subagentAgentId,
            String subagentParentSessionId,
            String subagentAssignment,
            com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding subagentDefinitionBinding,
            com.h.backend.chat.domain.approval.ApprovalMode approvalMode
    ) {
        private boolean subagent() {
            return gatewaySubagentId != null;
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
        if (AgentRegistry.STANDARD_CHAT_AGENT_ID.equals(agentId)) {
            return userId + ":" + resolvedPromptId + ":" + sessionId;
        }
        return chatMemoryIdFactory.executionId(userId, sessionId, agentId);
    }

    private void emitImageCommandEvents(
            FluxSink<ChatStreamEvent> sink,
            Long userId,
            Long resolvedPromptId,
            String sessionId,
            String userMessage,
            List<ChatMessageResourceUseDto> resources
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
        Long userMessageId = chatSessionService.appendUserMessage(userId, sessionId, userMessage, resources);
        ChatSessionMessageDto persistedUserMessage = chatSessionService.getOwnedMessage(userId, sessionId, userMessageId);
        emitIfActive(sink, new ChatStreamEvent("user_message", "", persistedUserMessage));
        String sourceResourceId = firstReferenceResourceId(resources);
        try {
            ChatSessionMessageDto message = imageGenerationService.generateImage(
                    new ImageGenerationService.ImageGenerationCommand(
                            userId,
                            sessionId,
                            resolvedPromptId,
                            imagePrompt,
                            "COMMAND",
                            sourceResourceId,
                            null,
                            "GENERATE"
                    )
            );
            emitIfActive(sink, new ChatStreamEvent("image", "", message));
            emitAndCompleteIfActive(sink, new ChatStreamEvent("done", ""));
        } catch (Exception ex) {
            log.error("Error generating image", ex);
            emitAndCompleteIfActive(sink, new ChatStreamEvent("error", "图片生成失败，请稍后重试"));
        }
    }

    private String firstReferenceResourceId(List<ChatMessageResourceUseDto> resources) {
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        return resources.stream()
                .filter(resource -> "REFERENCE".equalsIgnoreCase(resource.role()))
                .map(ChatMessageResourceUseDto::resourceId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Agent 输入语义内容：用户消息正文 + 已落库资源引用（设计 §9.9：用户上传资源在
     * 后续 Agent 输入中引用实际 resourceId，观测不读二进制、不反查数据库）。
     * 含 artifact 引用时 captureState=REFERENCE；映射全部失败时受限 CAPTURE_ERROR。
     */
    private static SemanticContent agentInputContent(ChatSessionMessageDto message, String userMessage) {
        List<ChatMessageResourceDto> resources =
                message == null || message.resources() == null ? List.of() : message.resources();
        if (resources.isEmpty()) {
            return SemanticContent.ofMessages(List.of(SemanticMessage.of("user", userMessage)));
        }
        List<ArtifactReferenceBlock> artifacts =
                BusinessArtifactReferenceMapper.referenceBlocks(resources, ArtifactUse.MODEL_INPUT);
        ContentCaptureState state = artifacts.isEmpty()
                ? ContentCaptureState.CAPTURE_ERROR
                : ContentCaptureState.REFERENCE;
        return new SemanticContent(
                List.of(SemanticMessage.of("user", userMessage)),
                List.copyOf(artifacts),
                state
        );
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

    private boolean isStandardImageCommand(String agentId, String userMessage) {
        String resolvedAgentId = StringUtils.isBlank(agentId)
                ? AgentRegistry.STANDARD_CHAT_AGENT_ID
                : agentId;
        return AgentRegistry.STANDARD_CHAT_AGENT_ID.equals(resolvedAgentId) && isImageCommand(userMessage);
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
            ChatStreamEventBridge chatStreamEventBridge,
            com.h.backend.memory.application.SuccessfulTurnCommitter successfulTurnCommitter,
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
                    chatStreamEventBridge,
                    successfulTurnCommitter
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

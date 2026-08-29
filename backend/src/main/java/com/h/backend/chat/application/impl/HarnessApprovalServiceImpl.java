package com.h.backend.chat.application.impl;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ApprovalRequestService;
import com.h.backend.chat.application.ChatStreamConcurrencyGuard;
import com.h.backend.chat.application.HarnessApprovalService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessExecutionSession;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.ChatAgentExecutionCommand;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.agent.HarnessAgentExecutor;
import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.domain.model.AgentRunSummary;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class HarnessApprovalServiceImpl implements HarnessApprovalService {

    private final ApprovalRequestService approvalRequestService;
    private final AgentRunService agentRunService;
    private final AgentObservability observability;
    private final ChatStreamConcurrencyGuard concurrencyGuard;
    private final HarnessCollaborationService collaborationService;
    private final AgentRegistry agentRegistry;
    private final HarnessAgentExecutor executor;

    public HarnessApprovalServiceImpl(
            ApprovalRequestService approvalRequestService,
            AgentRunService agentRunService,
            AgentObservability observability,
            ChatStreamConcurrencyGuard concurrencyGuard,
            HarnessCollaborationService collaborationService,
            AgentRegistry agentRegistry,
            HarnessAgentExecutor executor
    ) {
        this.approvalRequestService = approvalRequestService;
        this.agentRunService = agentRunService;
        this.observability = observability;
        this.concurrencyGuard = concurrencyGuard;
        this.collaborationService = collaborationService;
        this.agentRegistry = agentRegistry;
        this.executor = executor;
    }

    @Override
    public ApprovalRequestDto findPending(Long userId, String sessionId) {
        collaborationService.resolveExecutionSession(userId, sessionId);
        return approvalRequestService.findPending(userId, sessionId);
    }

    @Override
    public Flux<ChatStreamEvent> decideAndResume(
            Long userId,
            String approvalId,
            ApprovalDecision decision
    ) {
        return Flux.create(sink -> {
            ApprovalRequestDto pending = approvalRequestService.getOwned(userId, approvalId);
            if (pending.status() != com.h.backend.chat.domain.approval.ApprovalRequestStatus.PENDING) {
                sink.error(new BusinessException(40901, "审批请求已处理，请刷新会话"));
                return;
            }
            ChatStreamConcurrencyGuard.Permit permit =
                    concurrencyGuard.tryAcquire(pending.sessionId(), userId);
            if (!permit.acquired()) {
                sink.error(new BusinessException(40901, permit.message()));
                return;
            }
            AtomicBoolean released = new AtomicBoolean();
            Runnable release = () -> {
                if (released.compareAndSet(false, true)) {
                    permit.release();
                }
            };
            boolean resumed = false;
            try {
                HarnessExecutionSession address = collaborationService.resolveExecutionSession(
                        userId, pending.sessionId()
                );
                AgentRunSummary run = agentRunService.getById(pending.runId());
                if (run == null || !userId.equals(run.userId())
                        || !pending.sessionId().equals(run.sessionId())) {
                    throw new BusinessException(40404, "审批运行不存在");
                }
                ApprovalRequestService.ApprovalResolution resolution =
                        approvalRequestService.decide(userId, approvalId, decision);
                resumed = true;
                AgentDefinition agent = agentRegistry.requireEnabled(ChatAgentIds.HARNESS);
                AgentExecutionObservation observation = startResumeObservation(
                        userId, pending, address, run, agent
                );
                executor.resumeApproval(
                        new ChatAgentExecutionCommand(
                                sink, userId, run.promptId(), pending.sessionId(),
                                pending.rootSessionId(), address.gatewaySubagentId(),
                                address.subagentAgentId(), address.parentSessionId(),
                                address.assignment(), pending.subagentExecutionId(),
                                address.definitionBinding(), "", List.of(),
                                "approval-resume:" + pending.runId(), agent,
                                new AgentRunService.AgentRunHandle(pending.runId()), observation,
                                pending.approvalMode(), release, run.userMessageId()
                        ),
                        resolution.toolCallIds(),
                        resolution.approved()
                );
            } catch (Throwable error) {
                if (resumed) {
                    agentRunService.failRun(pending.runId(),
                            error.getMessage() == null ? "审批恢复失败" : error.getMessage());
                }
                release.run();
                sink.error(error);
            }
        });
    }

    private AgentExecutionObservation startResumeObservation(
            Long userId,
            ApprovalRequestDto pending,
            HarnessExecutionSession address,
            AgentRunSummary run,
            AgentDefinition agent
    ) {
        Map<String, String> propagationHeaders = run.traceParent() == null || run.traceParent().isBlank()
                ? Map.of()
                : Map.of("traceparent", run.traceParent());
        try (ObservationScope ignored = observability.scope(observability.extract(propagationHeaders))) {
            return observability.start(new AgentExecutionStart(
                    "agent.run.resume",
                    pending.rootSessionId(),
                    userId,
                    agent.agentId(),
                    pending.sessionId(),
                    address.subagent() ? "subagent" : "chat",
                    String.valueOf(pending.runId()),
                    List.of("hitl-resume"),
                    Map.of("chat.hitl.resume", "true"),
                    null
            ));
        }
    }
}

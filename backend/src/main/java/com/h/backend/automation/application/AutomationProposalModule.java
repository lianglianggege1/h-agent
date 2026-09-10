package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationProposalAction;
import com.h.backend.automation.domain.AutomationProposalStatus;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 提案模块：聊天内写操作只生成提案（PENDING，24 小时有效），用户通过当前会话卡片确认；
 * 确认时重新校验所有权、Agent 与基线版本，重复确认返回首次结果。
 */
@Service
public class AutomationProposalModule {

    private static final Logger log = LoggerFactory.getLogger(AutomationProposalModule.class);
    static final Duration PROPOSAL_TTL = Duration.ofHours(24);

    private final AutomationProposalRepository repository;
    private final AutomationTaskService taskService;
    private final AutomationAuditRecorder auditRecorder;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public AutomationProposalModule(
            AutomationProposalRepository repository,
            AutomationTaskService taskService,
            AutomationAuditRecorder auditRecorder,
            AgentRunService agentRunService,
            ObjectMapper objectMapper
    ) {
        this(repository, taskService, auditRecorder, agentRunService, objectMapper, Clock.systemUTC());
    }

    AutomationProposalModule(
            AutomationProposalRepository repository,
            AutomationTaskService taskService,
            AutomationAuditRecorder auditRecorder,
            AgentRunService agentRunService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.taskService = taskService;
        this.auditRecorder = auditRecorder;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AutomationProposal createChangeProposal(
            Long userId,
            AutomationProposalAction action,
            String taskId,
            AutomationTaskCommand command,
            String sourceSessionId,
            String createdVia
    ) {
        Instant now = clock.instant();
        Long baseRevision = null;
        String payload;
        switch (action) {
            case CREATE -> {
                AutomationTaskService.ValidatedCommand validated =
                        taskService.validateCommandForSession(userId, sourceSessionId, command);
                payload = writePayload(validated, sourceSessionId);
            }
            case UPDATE -> {
                AutomationTask current = taskService.requireOwned(userId, requireTaskId(taskId));
                AutomationTaskService.ValidatedCommand validated = taskService.validateCommand(command);
                payload = writePayload(validated, sourceSessionId);
                baseRevision = current.revision();
            }
            case ENABLE, DISABLE, DELETE -> {
                AutomationTask current = taskService.requireOwned(userId, requireTaskId(taskId));
                baseRevision = current.revision();
                payload = "{}";
            }
            default -> throw new BusinessException(40035, "不支持的提案类型：" + action);
        }
        Long sourceAgentRunId = requireOpenRunId(userId, sourceSessionId);
        AutomationProposal proposal = new AutomationProposal(
                UUID.randomUUID().toString(), userId, taskId, action.name(), payload, baseRevision,
                AutomationProposalStatus.PENDING.name(), sourceSessionId,
                createdVia == null || createdVia.isBlank() ? "CHAT" : createdVia,
                UUID.randomUUID().toString(), null, sourceAgentRunId, now.plus(PROPOSAL_TTL),
                null, null, now, now
        );
        AutomationProposal inserted = repository.insert(proposal);
        audit(new AutomationAuditEntry(userId, "PROPOSAL_CREATED", "PROPOSAL", inserted.id(),
                null, null, inserted.idempotencyKey(), null, now));
        return inserted;
    }

    /**
     * 提案必须绑定当前会话唯一开放的 AgentRun。找不到或存在多个时由 requireOpenRun 抛错，
     * 绝不回退到猜测最近一条消息，避免卡片锚定到错误轮次。
     */
    private Long requireOpenRunId(Long userId, String sessionId) {
        return agentRunService.requireOpenRun(userId, sessionId).id();
    }

    @Transactional
    public AutomationProposal confirm(Long userId, String proposalId) {
        Instant now = clock.instant();
        AutomationProposal proposal = repository.findOwnedForUpdate(userId, proposalId)
                .orElseThrow(() -> new BusinessException(40404, "自动化提案不存在"));
        if (AutomationProposalStatus.CONFIRMED.name().equals(proposal.status())) {
            return proposal;
        }
        if (AutomationProposalStatus.DISCARDED.name().equals(proposal.status())) {
            throw new BusinessException(40936, "提案已取消，请重新生成");
        }
        if (proposal.expiresAt().isBefore(now)) {
            repository.markExpired(now);
            throw new BusinessException(40937, "提案已过期，请重新生成");
        }
        AutomationProposalAction action = AutomationProposalAction.valueOf(proposal.action());
        String resultTaskId = apply(userId, action, proposal, now);
        AutomationProposal confirmed = repository.markConfirmed(proposal.id(), resultTaskId, userId, now);
        AutomationProposal result = confirmed != null ? confirmed : proposal;
        audit(new AutomationAuditEntry(userId, "PROPOSAL_CONFIRMED", "PROPOSAL", proposal.id(),
                proposal.baseTaskRevision(), null, proposal.idempotencyKey(),
                "{\"action\":\"" + action.name() + "\",\"taskId\":\"" + resultTaskId + "\"}", now));
        return result;
    }

    public AutomationProposal discard(Long userId, String proposalId) {
        Instant now = clock.instant();
        AutomationProposal discarded = repository.markDiscarded(userId, proposalId, now);
        if (discarded == null) {
            throw new BusinessException(40404, "自动化提案不存在或已处理");
        }
        audit(new AutomationAuditEntry(userId, "PROPOSAL_DISCARDED", "PROPOSAL", proposalId,
                null, null, null, null, now));
        return discarded;
    }

    public List<AutomationProposal> listPending(Long userId) {
        return repository.listPendingOwned(userId, clock.instant());
    }

    public List<AutomationProposal> listForSession(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40031, "sessionId 不能为空");
        }
        repository.markExpired(clock.instant());
        return repository.listOwnedBySession(userId, sessionId);
    }

    public List<AnchoredProposalView> listAnchoredForSession(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(40031, "sessionId 不能为空");
        }
        repository.markExpired(clock.instant());
        return repository.listAnchoredBySession(userId, sessionId);
    }

    /** 组装确认页视图：CREATE/UPDATE 提案附带解析后的调度字段与未来 3 次触发时刻。 */
    public ProposalView describe(AutomationProposal proposal) {
        AutomationProposalAction action = AutomationProposalAction.valueOf(proposal.action());
        if (action == AutomationProposalAction.CREATE || action == AutomationProposalAction.UPDATE) {
            try {
                AutomationTaskCommand command = readCommand(proposal.payloadJson());
                com.h.backend.automation.domain.AutomationSchedule schedule =
                        new com.h.backend.automation.domain.AutomationSchedule(
                                command.cronExpression(), command.zoneId());
                List<Instant> fires = schedule.upcomingFires(clock.instant(), 3);
                return new ProposalView(proposal, command.name(), command.instruction(), command.agentId(),
                        command.cronExpression(), command.zoneId(),
                        command.deliverySink(), fires);
            } catch (RuntimeException error) {
                log.warn("Proposal payload unreadable proposalId={}: {}", proposal.id(), error.getMessage());
            }
        }
        return new ProposalView(proposal, null, null, null, null, null, null, List.of());
    }

    /** 提案确认页视图数据。 */
    public record ProposalView(
            AutomationProposal proposal,
            String name,
            String instruction,
            String agentId,
            String cronExpression,
            String zoneId,
            String deliverySink,
            List<Instant> upcomingFires
    ) {
    }

    public int sweepExpired() {
        int expired = repository.markExpired(clock.instant());
        if (expired > 0) {
            log.info("Automation proposals expired: {}", expired);
        }
        return expired;
    }

    private String apply(
            Long userId,
            AutomationProposalAction action,
            AutomationProposal proposal,
            Instant now
    ) {
        switch (action) {
            case CREATE -> {
                AutomationTaskCommand command = readCommand(proposal.payloadJson());
                AutomationTask created = taskService.create(
                        userId, command, "PROPOSAL:" + proposal.createdVia(), proposal.sourceSessionId());
                return taskService.enable(userId, created.id(), created.revision()).id();
            }
            case UPDATE -> {
                AutomationTask current = taskService.requireOwned(userId, proposal.taskId());
                ensureBaseline(current, proposal);
                AutomationTask updated = taskService.update(
                        userId, proposal.taskId(), proposal.baseTaskRevision(), readCommand(proposal.payloadJson())
                );
                return updated.id();
            }
            case ENABLE -> {
                AutomationTask current = taskService.requireOwned(userId, proposal.taskId());
                ensureBaseline(current, proposal);
                return taskService.enable(userId, proposal.taskId(), proposal.baseTaskRevision()).id();
            }
            case DISABLE -> {
                AutomationTask current = taskService.requireOwned(userId, proposal.taskId());
                ensureBaseline(current, proposal);
                return taskService.disable(userId, proposal.taskId(), proposal.baseTaskRevision()).id();
            }
            case DELETE -> {
                AutomationTask current = taskService.requireOwned(userId, proposal.taskId());
                ensureBaseline(current, proposal);
                taskService.delete(userId, proposal.taskId());
                return proposal.taskId();
            }
            default -> throw new BusinessException(40035, "不支持的提案类型：" + action);
        }
    }

    private static void ensureBaseline(AutomationTask current, AutomationProposal proposal) {
        if (proposal.baseTaskRevision() == null || current.revision() != proposal.baseTaskRevision()) {
            throw new BusinessException(40938, "任务在提案生成后已变更，请重新生成提案");
        }
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(40031, "taskId 不能为空");
        }
        return taskId;
    }

    private String writePayload(AutomationTaskService.ValidatedCommand validated, String sourceSessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", validated.name());
        payload.put("instruction", validated.instruction());
        payload.put("agentId", validated.agentId());
        payload.put("runtime", validated.runtime().name());
        payload.put("cronExpression", validated.schedule().cronExpression());
        payload.put("zoneId", validated.schedule().zoneId());
        String sink = validated.deliverySessionId() != null
                ? AutomationDeliverySink.SESSION.name() : validated.deliverySink();
        payload.put("deliverySink", sink);
        payload.put("deliverySessionId", validated.deliverySessionId() != null
                ? validated.deliverySessionId() : sourceSessionId);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化提案内容", error);
        }
    }

    private AutomationTaskCommand readCommand(String payloadJson) {
        try {
            Map<?, ?> payload = objectMapper.readValue(payloadJson, Map.class);
            return new AutomationTaskCommand(
                    (String) payload.get("name"),
                    (String) payload.get("instruction"),
                    (String) payload.get("agentId"),
                    payload.get("runtime") == null ? null : com.h.backend.automation.domain.AutomationRuntime.valueOf(
                            (String) payload.get("runtime")),
                    (String) payload.get("cronExpression"),
                    (String) payload.get("zoneId"),
                    false,
                    (String) payload.get("deliverySink"),
                    (String) payload.get("deliverySessionId")
            );
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(40939, "提案内容已损坏，请重新生成");
        }
    }

    private void audit(AutomationAuditEntry entry) {
        auditRecorder.record(entry);
    }
}

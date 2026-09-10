package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.h.backend.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AutomationTaskService {

    static final int MAX_ENABLED_TASKS = 3;
    static final int ACTIVE_LIMIT_ERROR_CODE = 40934;
    static final String ACTIVE_LIMIT_MESSAGE = "最多同时开启 3 个自动化任务，请先关闭一个任务后再试。";

    private final AutomationTaskRepository repository;
    private final AgentRegistry agentRegistry;
    private final AutomationAuditRecorder auditRecorder;
    private final ChatSessionService chatSessionService;
    private final AutomationProperties properties;
    private final Clock clock;

    @Autowired
    public AutomationTaskService(
            AutomationTaskRepository repository,
            AgentRegistry agentRegistry,
            AutomationAuditRecorder auditRecorder,
            ChatSessionService chatSessionService,
            AutomationProperties properties
    ) {
        this(repository, agentRegistry, auditRecorder, chatSessionService, properties, Clock.systemUTC());
    }

    AutomationTaskService(AutomationTaskRepository repository, AgentRegistry agentRegistry) {
        this(repository, agentRegistry, AutomationAuditRecorder.NOOP, null, null, Clock.systemUTC());
    }

    AutomationTaskService(AutomationTaskRepository repository, AgentRegistry agentRegistry, Clock clock) {
        this(repository, agentRegistry, AutomationAuditRecorder.NOOP, null, null, clock);
    }

    AutomationTaskService(
            AutomationTaskRepository repository,
            AgentRegistry agentRegistry,
            AutomationAuditRecorder auditRecorder,
            ChatSessionService chatSessionService,
            AutomationProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.agentRegistry = agentRegistry;
        this.auditRecorder = auditRecorder;
        this.chatSessionService = chatSessionService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 校验并归一化命令；提案创建与确认、管理页直写共用同一套规则。 */
    public ValidatedCommand validateCommand(AutomationTaskCommand command) {
        if (command == null) {
            throw new BusinessException(40031, "自动化任务参数不能为空");
        }
        String name = required(command.name(), "任务名称", 120);
        String instruction = required(command.instruction(), "任务内容", 20_000);
        String agentId = required(command.agentId(), "Agent", 128);
        AutomationSchedule schedule;
        try {
            schedule = new AutomationSchedule(command.cronExpression(), command.zoneId());
        } catch (RuntimeException error) {
            throw new BusinessException(40032, error.getMessage());
        }
        AgentDefinition agent = agentRegistry.requireEnabled(agentId);
        AutomationRuntime actualRuntime = AutomationRuntime.forAgentRuntime(agent.runtimeType());
        AutomationRuntime requestedRuntime = command.runtime() == null ? actualRuntime : command.runtime();
        if (requestedRuntime != actualRuntime) {
            throw new BusinessException(40033, "所选 Agent 与运行时不匹配");
        }
        String deliverySink = normalizeDeliverySink(command.deliverySink());
        String deliverySessionId = normalizeDeliverySession(deliverySink, command.deliverySessionId());
        return new ValidatedCommand(
                name, instruction, agent.agentId(), actualRuntime, schedule, deliverySink, deliverySessionId
        );
    }

    public ValidatedCommand validateCommandForSession(
            Long userId,
            String sessionId,
            AutomationTaskCommand command
    ) {
        String boundSessionId = required(sessionId, "执行会话", 64);
        return validateCommand(bindToSession(userId, boundSessionId, command));
    }

    @Transactional
    public AutomationTask create(
            Long userId,
            AutomationTaskCommand command,
            String createdVia,
            String sourceSessionId
    ) {
        String sessionId = required(sourceSessionId, "执行会话", 64);
        ValidatedCommand validated = validateCommandForSession(userId, sessionId, command);
        Instant now = clock.instant();
        AutomationTask task = new AutomationTask(
                UUID.randomUUID().toString(), userId, validated.name(), validated.instruction(),
                validated.agentId(), validated.runtime(), validated.schedule(), false,
                null, null, null, normalizeCreatedVia(createdVia), 1L, now, now,
                validated.deliverySink(), validated.deliverySessionId(), sessionId
        );
        AutomationTask inserted = repository.insert(task);
        audit(AutomationAuditEntry.of(userId, "TASK_CREATED", "TASK", inserted.id(),
                null, inserted.revision(), now));
        return inserted;
    }

    @Transactional
    public AutomationTask update(Long userId, String taskId, long expectedRevision, AutomationTaskCommand command) {
        AutomationTask current = requireOwned(userId, taskId);
        ValidatedCommand validated = validateCommand(bindToSession(userId, current.sessionId(), command));
        boolean enabled = current.enabled();
        Instant now = clock.instant();
        AutomationTask replacement = new AutomationTask(
                current.id(), current.userId(), validated.name(), validated.instruction(),
                validated.agentId(), validated.runtime(), validated.schedule(), enabled,
                enabled ? validated.schedule().nextAfter(now) : null,
                current.lastRunAt(), current.lastStatus(), current.createdVia(), current.revision() + 1,
                current.createdAt(), now,
                validated.deliverySink(), validated.deliverySessionId(), current.sessionId()
        );
        AutomationTask updated = repository.updateOwned(userId, taskId, expectedRevision, replacement);
        if (updated == null) {
            throw revisionConflict();
        }
        audit(AutomationAuditEntry.of(userId, "TASK_UPDATED", "TASK", updated.id(),
                expectedRevision, updated.revision(), now));
        return updated;
    }

    @Transactional
    public AutomationTask enable(Long userId, String taskId, long expectedRevision) {
        if (properties != null && !properties.getXxlJob().isEnabled()) {
            throw new BusinessException(50340, "XXL-Job 未启用，不能开启自动化任务");
        }
        AutomationTask current = requireOwned(userId, taskId);
        if (current.enabled()) {
            return current;
        }
        Instant now = clock.instant();
        AutomationTask enabled = repository.enableOwned(
                userId, taskId, expectedRevision, current.schedule().nextAfter(now), now, MAX_ENABLED_TASKS
        );
        if (enabled != null) {
            audit(AutomationAuditEntry.of(userId, "TASK_ENABLED", "TASK", enabled.id(),
                    expectedRevision, enabled.revision(), now));
            return enabled;
        }
        AutomationTask latest = requireOwned(userId, taskId);
        if (latest.enabled()) {
            return latest;
        }
        if (latest.revision() != expectedRevision) {
            throw revisionConflict();
        }
        throw new BusinessException(ACTIVE_LIMIT_ERROR_CODE, ACTIVE_LIMIT_MESSAGE);
    }

    @Transactional
    public AutomationTask disable(Long userId, String taskId, long expectedRevision) {
        AutomationTask current = requireOwned(userId, taskId);
        if (!current.enabled()) {
            return current;
        }
        Instant now = clock.instant();
        AutomationTask disabled = repository.disableOwned(userId, taskId, expectedRevision, now);
        if (disabled != null) {
            audit(AutomationAuditEntry.of(userId, "TASK_DISABLED", "TASK", disabled.id(),
                    expectedRevision, disabled.revision(), now));
            return disabled;
        }
        AutomationTask latest = requireOwned(userId, taskId);
        if (!latest.enabled()) {
            return latest;
        }
        throw revisionConflict();
    }

    public List<AutomationTask> list(Long userId) {
        return repository.listOwned(userId);
    }

    public AutomationTask requireOwned(Long userId, String taskId) {
        return repository.findOwned(userId, taskId)
                .orElseThrow(() -> new BusinessException(40404, "自动化任务不存在"));
    }

    /** 接纳后策略复验：Agent 停用/凭证失效等，失败由协调器记为 REJECTED_POLICY。 */
    public void assertAgentRunnable(String agentId) {
        agentRegistry.requireEnabled(agentId);
    }

    @Transactional
    public void delete(Long userId, String taskId) {
        AutomationTask current = requireOwned(userId, taskId);
        Instant now = clock.instant();
        if (!repository.softDeleteOwned(userId, taskId)) {
            throw new BusinessException(40404, "自动化任务不存在");
        }
        audit(AutomationAuditEntry.of(userId, "TASK_DELETED", "TASK", taskId,
                current.revision(), current.revision() + 1, now));
    }

    public List<AutomationRun> runs(Long userId, String taskId, int limit) {
        requireOwned(userId, taskId);
        return repository.listRunsOwned(userId, taskId, Math.min(Math.max(limit, 1), 100));
    }

    private static String normalizeDeliverySink(String value) {
        if (value == null || value.isBlank()) {
            return AutomationDeliverySink.NONE.name();
        }
        try {
            return AutomationDeliverySink.valueOf(value.trim().toUpperCase()).name();
        } catch (IllegalArgumentException error) {
            throw new BusinessException(40034, "不支持的投递方式：" + value);
        }
    }

    private static String normalizeDeliverySession(String deliverySink, String sessionId) {
        if (AutomationDeliverySink.SESSION.name().equals(deliverySink)) {
            if (sessionId == null || sessionId.isBlank()) {
                throw new BusinessException(40034, "SESSION 投递必须指定目标会话");
            }
            return sessionId.trim();
        }
        return null;
    }

    private void audit(AutomationAuditEntry entry) {
        auditRecorder.record(entry);
    }

    private AutomationTaskCommand bindToSession(
            Long userId,
            String sessionId,
            AutomationTaskCommand command
    ) {
        if (chatSessionService == null) {
            return command;
        }
        if (command == null) {
            return null;
        }
        var session = chatSessionService.getSessionDetail(userId, sessionId);
        return new AutomationTaskCommand(
                command.name(), command.instruction(), session.agentId(), null,
                command.cronExpression(), command.zoneId(), command.enabled(),
                command.deliverySink(), command.deliverySessionId()
        );
    }

    private static String required(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(40031, label + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(40031, label + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeCreatedVia(String createdVia) {
        return createdVia == null || createdVia.isBlank() ? "UI" : createdVia;
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(40931, "自动化任务已被其他请求修改，请刷新后重试");
    }

    /** 校验后的归一化命令；提案与任务创建都以它为准。 */
    public record ValidatedCommand(
            String name,
            String instruction,
            String agentId,
            AutomationRuntime runtime,
            AutomationSchedule schedule,
            String deliverySink,
            String deliverySessionId
    ) {
    }
}

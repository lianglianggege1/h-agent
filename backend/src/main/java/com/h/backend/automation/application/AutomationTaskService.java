package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AutomationTaskService {

    private final AutomationTaskRepository repository;
    private final AgentRegistry agentRegistry;
    private final Clock clock;

    @Autowired
    public AutomationTaskService(AutomationTaskRepository repository, AgentRegistry agentRegistry) {
        this(repository, agentRegistry, Clock.systemUTC());
    }

    AutomationTaskService(AutomationTaskRepository repository, AgentRegistry agentRegistry, Clock clock) {
        this.repository = repository;
        this.agentRegistry = agentRegistry;
        this.clock = clock;
    }

    public AutomationTask create(Long userId, AutomationTaskCommand command, String createdVia) {
        Validated validated = validate(command);
        Instant now = clock.instant();
        boolean enabled = command.enabled() == null || command.enabled();
        AutomationTask task = new AutomationTask(
                UUID.randomUUID().toString(), userId, validated.name(), validated.instruction(),
                validated.agent().agentId(), validated.runtime(), validated.schedule(), enabled,
                enabled ? validated.schedule().nextAfter(now) : null,
                null, null, normalizeCreatedVia(createdVia), 1L, now, now
        );
        return repository.insert(task);
    }

    public AutomationTask update(Long userId, String taskId, long expectedRevision, AutomationTaskCommand command) {
        AutomationTask current = requireOwned(userId, taskId);
        Validated validated = validate(command);
        boolean enabled = command.enabled() == null ? current.enabled() : command.enabled();
        Instant now = clock.instant();
        AutomationTask replacement = new AutomationTask(
                current.id(), current.userId(), validated.name(), validated.instruction(),
                validated.agent().agentId(), validated.runtime(), validated.schedule(), enabled,
                enabled ? validated.schedule().nextAfter(now) : null,
                current.lastRunAt(), current.lastStatus(), current.createdVia(), current.revision() + 1,
                current.createdAt(), now
        );
        AutomationTask updated = repository.updateOwned(userId, taskId, expectedRevision, replacement);
        if (updated == null) {
            throw new BusinessException(40931, "自动化任务已被其他请求修改，请刷新后重试");
        }
        return updated;
    }

    public List<AutomationTask> list(Long userId) {
        return repository.listOwned(userId);
    }

    public AutomationTask requireOwned(Long userId, String taskId) {
        return repository.findOwned(userId, taskId)
                .orElseThrow(() -> new BusinessException(40404, "自动化任务不存在"));
    }

    public void delete(Long userId, String taskId) {
        if (!repository.softDeleteOwned(userId, taskId)) {
            throw new BusinessException(40404, "自动化任务不存在");
        }
    }

    public List<AutomationRun> runs(Long userId, String taskId, int limit) {
        requireOwned(userId, taskId);
        return repository.listRunsOwned(userId, taskId, Math.min(Math.max(limit, 1), 100));
    }

    private Validated validate(AutomationTaskCommand command) {
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
        return new Validated(name, instruction, agent, actualRuntime, schedule);
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

    private record Validated(
            String name,
            String instruction,
            AgentDefinition agent,
            AutomationRuntime runtime,
            AutomationSchedule schedule
    ) {
    }
}

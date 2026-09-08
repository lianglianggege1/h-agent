package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRunStatus;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.h.backend.automation.infrastructure.execution.AutomationPolicyRejectionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 触发接纳：至少一次触发、至多一次接纳。
 * 校验任务 ACTIVE、版本匹配、重叠策略，把观测时刻归一化到逻辑日程点（misfire 窗口内），
 * 用数据库唯一键接纳一次 Run，并冻结 ExecutionSpec 快照。
 */
@Service
public class RunAdmissionModule {

    private final AutomationTaskRepository repository;
    private final AutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public RunAdmissionModule(
            AutomationTaskRepository repository,
            AutomationProperties properties,
            ObjectMapper objectMapper
    ) {
        this(repository, properties, objectMapper, Clock.systemUTC());
    }

    RunAdmissionModule(AutomationTaskRepository repository, Clock clock) {
        this(repository, new AutomationProperties(), new ObjectMapper(), clock);
    }

    RunAdmissionModule(
            AutomationTaskRepository repository,
            AutomationProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AdmissionResult admitScheduled(
            String taskId,
            long taskRevision,
            Instant observedTriggerAt,
            String triggerId
    ) {
        AutomationTask task = repository.findById(taskId).orElse(null);
        if (task == null) {
            return new AdmissionResult(AdmissionStatus.SKIPPED_TASK_NOT_FOUND, null, null);
        }
        if (!task.enabled()) {
            return new AdmissionResult(AdmissionStatus.SKIPPED_DISABLED, task, null);
        }
        if (task.revision() != taskRevision) {
            return new AdmissionResult(AdmissionStatus.SKIPPED_STALE_REVISION, task, null);
        }
        if (repository.existsActiveRun(taskId)) {
            return new AdmissionResult(AdmissionStatus.SKIPPED_OVERLAP, task, null);
        }
        Instant scheduledFor = task.schedule()
                .latestFireWithin(observedTriggerAt, properties.getMisfireTolerance())
                .orElse(null);
        if (scheduledFor == null) {
            return new AdmissionResult(AdmissionStatus.SKIPPED_MISFIRE, task, null);
        }
        String runId = UUID.randomUUID().toString();
        AutomationRun run = new AutomationRun(
                runId, task.id(), task.userId(), task.revision(),
                "SCHEDULED", triggerId, AutomationRunStatus.QUEUED.name(), scheduledFor, clock.instant(),
                null, null, null, null, null, snapshot(task, runId, "SCHEDULED", scheduledFor)
        );
        AutomationRun inserted = repository.insertScheduledRunIfAbsent(run);
        if (inserted == null) {
            // 数据库同时守护逻辑日程幂等键和“同一任务仅一个活跃 Run”。
            // 两个并发触发都通过前置查询时，由插入后的复验区分重叠与重复。
            AdmissionStatus conflict = repository.existsActiveRun(taskId)
                    ? AdmissionStatus.SKIPPED_OVERLAP : AdmissionStatus.SKIPPED_DUPLICATE;
            return new AdmissionResult(conflict, task, null);
        }
        return new AdmissionResult(AdmissionStatus.ACCEPTED, task, inserted);
    }

    String snapshot(AutomationTask task, String runId, String triggerType, Instant scheduledFor) {
        try {
            ExecutionSpec spec = ExecutionSpec.freeze(
                    task, runId, triggerType, scheduledFor,
                    properties.getExecutionTimeout().getSeconds(), clock.instant()
            );
            return objectMapper.writeValueAsString(spec);
        } catch (Exception error) {
            throw new IllegalStateException("无法冻结执行快照", error);
        }
    }

    public ExecutionSpec readSnapshot(AutomationRun run) {
        try {
            ExecutionSpec spec = objectMapper.readValue(run.specSnapshot(), ExecutionSpec.class);
            if (spec == null || !run.id().equals(spec.runId()) || !run.taskId().equals(spec.taskId())
                    || run.taskRevision() != spec.taskRevision() || spec.userId() == null) {
                throw new IllegalArgumentException("快照身份不匹配");
            }
            return spec;
        } catch (Exception error) {
            throw new AutomationPolicyRejectionException("执行快照无效，已拒绝运行", error);
        }
    }

    public enum AdmissionStatus {
        ACCEPTED,
        SKIPPED_DUPLICATE,
        SKIPPED_OVERLAP,
        SKIPPED_DISABLED,
        SKIPPED_STALE_REVISION,
        SKIPPED_MISFIRE,
        SKIPPED_TASK_NOT_FOUND
    }

    public record AdmissionResult(AdmissionStatus status, AutomationTask task, AutomationRun run) {

        public Optional<AutomationRun> acceptedRun() {
            return status == AdmissionStatus.ACCEPTED ? Optional.of(run) : Optional.empty();
        }
    }
}

package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AutomationTaskRepository;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationRunEntity;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationTaskEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationRunMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationTaskMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AutomationTaskRepositoryImpl implements AutomationTaskRepository {

    private final AutomationTaskMapper taskMapper;
    private final AutomationRunMapper runMapper;

    public AutomationTaskRepositoryImpl(AutomationTaskMapper taskMapper, AutomationRunMapper runMapper) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
    }

    @Override
    public AutomationTask insert(AutomationTask task) {
        taskMapper.insert(toEntity(task));
        return task;
    }

    @Override
    public AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement) {
        int changed = taskMapper.updateOwned(userId, taskId, expectedRevision, toEntity(replacement));
        return changed == 0 ? null : replacement;
    }

    @Override
    public Optional<AutomationTask> findOwned(Long userId, String taskId) {
        return Optional.ofNullable(taskMapper.selectOwned(userId, taskId)).map(this::toDomain);
    }

    @Override
    public List<AutomationTask> listOwned(Long userId) {
        return taskMapper.selectOwnedList(userId).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean softDeleteOwned(Long userId, String taskId) {
        return taskMapper.softDeleteOwned(userId, taskId, toLocal(Instant.now())) > 0;
    }

    @Override
    @Transactional
    public List<AutomationTask> claimDue(Instant now, int limit, String leaseOwner, Duration leaseDuration) {
        return taskMapper.claimDue(toLocal(now), limit, leaseOwner, toLocal(now.plus(leaseDuration)))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void releaseLease(
            String taskId,
            String leaseOwner,
            Instant nextRunAt,
            Instant lastRunAt,
            String lastStatus
    ) {
        taskMapper.releaseLease(taskId, leaseOwner, toLocal(nextRunAt), toLocal(lastRunAt), lastStatus);
    }

    @Override
    public void recordManualRunResult(String taskId, Instant lastRunAt, String lastStatus) {
        taskMapper.recordManualRunResult(taskId, toLocal(lastRunAt), lastStatus);
    }

    @Override
    public AutomationRun insertRun(AutomationRun run) {
        runMapper.insert(toEntity(run));
        return run;
    }

    @Override
    public void completeRun(
            String runId,
            String status,
            Instant finishedAt,
            String sessionId,
            String output,
            String errorMessage
    ) {
        runMapper.complete(runId, status, toLocal(finishedAt), sessionId, output, errorMessage);
    }

    @Override
    public List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit) {
        return runMapper.selectOwnedRuns(userId, taskId, limit).stream().map(this::toDomain).toList();
    }

    private AutomationTaskEntity toEntity(AutomationTask task) {
        AutomationTaskEntity entity = new AutomationTaskEntity();
        entity.setId(task.id());
        entity.setUserId(task.userId());
        entity.setName(task.name());
        entity.setInstruction(task.instruction());
        entity.setAgentId(task.agentId());
        entity.setRuntime(task.runtime().name());
        entity.setCronExpression(task.schedule().cronExpression());
        entity.setZoneId(task.schedule().zoneId());
        entity.setEnabled(task.enabled());
        entity.setNextRunAt(toLocal(task.nextRunAt()));
        entity.setLastRunAt(toLocal(task.lastRunAt()));
        entity.setLastStatus(task.lastStatus());
        entity.setCreatedVia(task.createdVia());
        entity.setRevision(task.revision());
        entity.setCreatedAt(toLocal(task.createdAt()));
        entity.setUpdatedAt(toLocal(task.updatedAt()));
        return entity;
    }

    private AutomationTask toDomain(AutomationTaskEntity entity) {
        return new AutomationTask(
                entity.getId(), entity.getUserId(), entity.getName(), entity.getInstruction(),
                entity.getAgentId(), AutomationRuntime.valueOf(entity.getRuntime()),
                new AutomationSchedule(entity.getCronExpression(), entity.getZoneId()),
                Boolean.TRUE.equals(entity.getEnabled()), toInstant(entity.getNextRunAt()),
                toInstant(entity.getLastRunAt()), entity.getLastStatus(), entity.getCreatedVia(),
                entity.getRevision() == null ? 1L : entity.getRevision(),
                toInstant(entity.getCreatedAt()), toInstant(entity.getUpdatedAt())
        );
    }

    private AutomationRunEntity toEntity(AutomationRun run) {
        AutomationRunEntity entity = new AutomationRunEntity();
        entity.setId(run.id());
        entity.setTaskId(run.taskId());
        entity.setUserId(run.userId());
        entity.setTriggerType(run.triggerType());
        entity.setStatus(run.status());
        entity.setScheduledFor(toLocal(run.scheduledFor()));
        entity.setStartedAt(toLocal(run.startedAt()));
        entity.setFinishedAt(toLocal(run.finishedAt()));
        entity.setSessionId(run.sessionId());
        entity.setOutput(run.output());
        entity.setErrorMessage(run.errorMessage());
        return entity;
    }

    private AutomationRun toDomain(AutomationRunEntity entity) {
        return new AutomationRun(
                entity.getId(), entity.getTaskId(), entity.getUserId(), entity.getTriggerType(),
                entity.getStatus(), toInstant(entity.getScheduledFor()), toInstant(entity.getStartedAt()),
                toInstant(entity.getFinishedAt()), entity.getSessionId(), entity.getOutput(),
                entity.getErrorMessage()
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}

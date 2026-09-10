package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AutomationTaskRepository;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationRunEntity;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationTaskEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationRunMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.SchedulerProjectionMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationTaskMapper;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AutomationTaskRepositoryImpl implements AutomationTaskRepository {

    private final AutomationTaskMapper taskMapper;
    private final AutomationRunMapper runMapper;
    private final SchedulerProjectionMapper schedulerProjectionMapper;
    private final AutomationProperties properties;

    public AutomationTaskRepositoryImpl(
            AutomationTaskMapper taskMapper,
            AutomationRunMapper runMapper,
            SchedulerProjectionMapper schedulerProjectionMapper,
            AutomationProperties properties
    ) {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.schedulerProjectionMapper = schedulerProjectionMapper;
        this.properties = properties;
    }

    @Override
    public AutomationTask insert(AutomationTask task) {
        taskMapper.insert(toEntity(task));
        return task;
    }

    @Override
    @Transactional
    public AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement) {
        schedulerProjectionMapper.lockTask(taskId);
        int changed = taskMapper.updateOwned(userId, taskId, expectedRevision, toEntity(replacement));
        if (changed == 0) {
            return null;
        }
        if (replacement.enabled()) {
            enqueueProjection(toEntity(replacement), "ACTIVE");
        }
        return replacement;
    }

    @Override
    @Transactional
    public AutomationTask enableOwned(
            Long userId,
            String taskId,
            long expectedRevision,
            Instant nextRunAt,
            Instant updatedAt,
            int maxEnabled
    ) {
        schedulerProjectionMapper.lockTask(taskId);
        AutomationTaskEntity entity = taskMapper.enableOwned(
                userId, taskId, expectedRevision, toLocal(nextRunAt), toLocal(updatedAt), maxEnabled
        );
        if (entity != null) {
            enqueueProjection(entity, "ACTIVE");
        }
        return entity == null ? null : toDomain(entity);
    }

    @Override
    @Transactional
    public AutomationTask disableOwned(Long userId, String taskId, long expectedRevision, Instant updatedAt) {
        schedulerProjectionMapper.lockTask(taskId);
        AutomationTaskEntity entity = taskMapper.disableOwned(userId, taskId, expectedRevision, toLocal(updatedAt));
        if (entity != null) {
            enqueueProjection(entity, "STOPPED");
        }
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public Optional<AutomationTask> findOwned(Long userId, String taskId) {
        return Optional.ofNullable(taskMapper.selectOwned(userId, taskId)).map(this::toDomain);
    }

    @Override
    public Optional<AutomationTask> findById(String taskId) {
        return Optional.ofNullable(taskMapper.selectByTaskId(taskId)).map(this::toDomain);
    }

    @Override
    public List<AutomationTask> listOwned(Long userId) {
        return taskMapper.selectOwnedList(userId).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional
    public boolean softDeleteOwned(Long userId, String taskId) {
        schedulerProjectionMapper.lockTask(taskId);
        AutomationTaskEntity entity = taskMapper.softDeleteOwned(userId, taskId, toLocal(Instant.now()));
        if (entity == null) {
            return false;
        }
        enqueueProjection(entity, "DELETED");
        return true;
    }

    @Override
    public void recordRunResult(String taskId, Instant at, String status) {
        taskMapper.recordRunResult(taskId, toLocal(at), status);
    }

    @Override
    public AutomationRun insertRun(AutomationRun run) {
        runMapper.insert(toEntity(run));
        return run;
    }

    @Override
    public AutomationRun insertManualRunIfNoActive(AutomationRun run) {
        return runMapper.insertManualIfNoActive(toEntity(run)) == 0 ? null : run;
    }

    @Override
    public AutomationRun insertScheduledRunIfAbsent(AutomationRun run) {
        return runMapper.insertScheduledIfAbsent(toEntity(run)) == 0 ? null : run;
    }

    @Override
    public boolean existsActiveRun(String taskId) {
        return runMapper.existsActiveRun(taskId);
    }

    @Override
    public Optional<AutomationRun> claimQueuedRun(String runId, Instant startedAt) {
        return Optional.ofNullable(runMapper.claimQueuedRun(runId, toLocal(startedAt))).map(this::toDomain);
    }

    @Override
    public Optional<AutomationRun> bindXxlTrigger(String runId, String triggerId) {
        return Optional.ofNullable(runMapper.bindXxlTrigger(runId, triggerId)).map(this::toDomain);
    }

    @Override
    public Optional<AutomationRun> findRunOwned(Long userId, String runId) {
        return Optional.ofNullable(runMapper.selectOwnedRun(userId, runId)).map(this::toDomain);
    }

    @Override
    public Optional<AutomationRun> requestCancelRun(String runId, Instant now) {
        return Optional.ofNullable(runMapper.requestCancel(runId, toLocal(now))).map(this::toDomain);
    }

    @Override
    public List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit) {
        return runMapper.selectOwnedRuns(userId, taskId, limit).stream().map(this::toDomain).toList();
    }

    @Override
    public List<AutomationRun> listActiveRunsStartedBefore(Instant before, int limit) {
        return runMapper.selectActiveStartedBefore(toLocal(before), limit).stream().map(this::toDomain).toList();
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
        entity.setDeliverySink(task.deliverySink() == null
                ? AutomationDeliverySink.NONE.name() : task.deliverySink());
        entity.setDeliverySessionId(task.deliverySessionId());
        entity.setSessionId(task.sessionId());
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
                toInstant(entity.getCreatedAt()), toInstant(entity.getUpdatedAt()),
                entity.getDeliverySink() == null ? AutomationDeliverySink.NONE.name() : entity.getDeliverySink(),
                entity.getDeliverySessionId(), entity.getSessionId()
        );
    }

    private AutomationRunEntity toEntity(AutomationRun run) {
        AutomationRunEntity entity = new AutomationRunEntity();
        entity.setId(run.id());
        entity.setTaskId(run.taskId());
        entity.setUserId(run.userId());
        entity.setTaskRevision(run.taskRevision());
        entity.setTriggerType(run.triggerType());
        entity.setTriggerId(run.triggerId());
        entity.setStatus(run.status());
        entity.setScheduledFor(toLocal(run.scheduledFor()));
        entity.setStartedAt(toLocal(run.startedAt()));
        entity.setFinishedAt(toLocal(run.finishedAt()));
        entity.setSessionId(run.sessionId());
        entity.setOutput(run.output());
        entity.setErrorMessage(run.errorMessage());
        entity.setCancelRequestedAt(toLocal(run.cancelRequestedAt()));
        entity.setSpecSnapshot(run.specSnapshot());
        return entity;
    }

    private AutomationRun toDomain(AutomationRunEntity entity) {
        return new AutomationRun(
                entity.getId(), entity.getTaskId(), entity.getUserId(),
                entity.getTaskRevision() == null ? 1L : entity.getTaskRevision(),
                entity.getTriggerType(), entity.getTriggerId(), entity.getStatus(),
                toInstant(entity.getScheduledFor()), toInstant(entity.getStartedAt()),
                toInstant(entity.getFinishedAt()), entity.getSessionId(), entity.getOutput(),
                entity.getErrorMessage(), toInstant(entity.getCancelRequestedAt()),
                entity.getSpecSnapshot()
        );
    }

    private void enqueueProjection(AutomationTaskEntity task, String desiredState) {
        if (!properties.getXxlJob().isEnabled()) {
            return;
        }
        String projectedState = "ACTIVE".equals(desiredState)
                && !properties.getXxlJob().getSchedulerZoneId().equals(task.getZoneId())
                ? "STOPPED" : desiredState;
        LocalDateTime now = task.getUpdatedAt() == null ? toLocal(Instant.now()) : task.getUpdatedAt();
        schedulerProjectionMapper.upsertDesired(task.getId(), task.getRevision(), projectedState, now);
        schedulerProjectionMapper.insertOutbox(
                UUID.randomUUID().toString(), task.getId(), task.getRevision(), projectedState,
                task.getName(), task.getCronExpression(), task.getZoneId(), now
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}

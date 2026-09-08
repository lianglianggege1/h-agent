package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.SchedulerProjectionGateway.DesiredState;
import com.h.backend.automation.application.SchedulerProjectionRepository;
import com.h.backend.automation.application.SchedulerProjectionWork;
import com.h.backend.automation.infrastructure.persistence.entity.SchedulerProjectionOutboxEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.SchedulerProjectionMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import com.h.backend.automation.application.SchedulerProjectionStatus;

@Repository
public class SchedulerProjectionRepositoryImpl implements SchedulerProjectionRepository {

    private final SchedulerProjectionMapper mapper;

    public SchedulerProjectionRepositoryImpl(SchedulerProjectionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public List<SchedulerProjectionWork> claim(Instant now, int limit, String leaseOwner, Duration leaseDuration) {
        return mapper.claim(toLocal(now), limit, leaseOwner, toLocal(now.plus(leaseDuration))).stream()
                .map(this::toWork)
                .toList();
    }

    @Override
    public void lockTask(String taskId) {
        mapper.lockTask(taskId);
    }

    @Override
    public boolean isCurrent(SchedulerProjectionWork item) {
        return mapper.isCurrent(item.taskId(), item.taskRevision(), item.desiredState().name());
    }

    @Override
    @Transactional
    public void complete(SchedulerProjectionWork item, Long jobId, Instant now) {
        mapper.markProjectionSynced(
                item.taskId(), item.taskRevision(), item.desiredState().name(), jobId, toLocal(now)
        );
        mapper.finishOutbox(item.outboxId(), "DONE", toLocal(now));
    }

    @Override
    public void discard(SchedulerProjectionWork item, Instant now) {
        mapper.finishOutbox(item.outboxId(), "DISCARDED", toLocal(now));
    }

    @Override
    @Transactional
    public void retry(SchedulerProjectionWork item, String errorMessage, Instant availableAt, Instant now) {
        mapper.markProjectionFailed(
                item.taskId(), item.taskRevision(), item.desiredState().name(), errorMessage, toLocal(now)
        );
        mapper.retryOutbox(item.outboxId(), errorMessage, toLocal(availableAt), toLocal(now));
    }

    @Override
    public Optional<SchedulerProjectionStatus> findStatus(String taskId) {
        return Optional.ofNullable(mapper.selectStatus(taskId));
    }

    private SchedulerProjectionWork toWork(SchedulerProjectionOutboxEntity entity) {
        return new SchedulerProjectionWork(
                entity.getId(), entity.getTaskId(), entity.getTaskRevision(),
                DesiredState.valueOf(entity.getDesiredState()), mapper.selectJobId(entity.getTaskId()),
                entity.getTaskName(), entity.getCronExpression(), entity.getZoneId(),
                entity.getAttemptCount() == null ? 0 : entity.getAttemptCount()
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

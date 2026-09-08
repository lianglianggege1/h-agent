package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.application.SchedulerProjectionStatus;

import java.time.Instant;

public record AutomationTaskDto(
        String id,
        String name,
        String instruction,
        String agentId,
        String runtime,
        String cronExpression,
        String zoneId,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastStatus,
        String createdVia,
        long revision,
        String deliverySink,
        String deliverySessionId,
        String schedulerMode,
        String schedulerSyncStatus,
        Long schedulerSyncedRevision,
        String schedulerSyncError,
        Instant createdAt,
        Instant updatedAt
) {
    public static AutomationTaskDto from(AutomationTask task) {
        return from(task, null, "LOCAL");
    }

    public static AutomationTaskDto from(AutomationTask task, SchedulerProjectionStatus projection) {
        return from(task, projection, "XXL_JOB");
    }

    public static AutomationTaskDto from(
            AutomationTask task, SchedulerProjectionStatus projection, String schedulerMode
    ) {
        return new AutomationTaskDto(
                task.id(), task.name(), task.instruction(), task.agentId(), task.runtime().name(),
                task.schedule().cronExpression(), task.schedule().zoneId(), task.enabled(),
                task.nextRunAt(), task.lastRunAt(), task.lastStatus(), task.createdVia(),
                task.revision(), task.deliverySink(), task.deliverySessionId(),
                schedulerMode,
                projection == null ? "NOT_SCHEDULED" : projection.syncStatus(),
                projection == null ? null : projection.syncedRevision(),
                projection == null ? null : projection.lastError(),
                task.createdAt(), task.updatedAt()
        );
    }
}

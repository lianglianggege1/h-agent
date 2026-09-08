package com.h.backend.automation.application;

import com.h.backend.automation.application.SchedulerProjectionGateway.DesiredState;

public record SchedulerProjectionWork(
        String outboxId,
        String taskId,
        long taskRevision,
        DesiredState desiredState,
        Long knownJobId,
        String taskName,
        String cronExpression,
        String zoneId,
        int attemptCount
) {
}

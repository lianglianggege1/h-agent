package com.h.backend.automation.application;

public record SchedulerProjectionStatus(
        String taskId,
        long desiredRevision,
        String desiredState,
        Long xxlJobId,
        Long syncedRevision,
        String syncStatus,
        String lastError
) {
}

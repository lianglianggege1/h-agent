package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationRun;

import java.time.Instant;

public record AutomationRunDto(
        String id,
        String taskId,
        long taskRevision,
        String triggerType,
        String triggerId,
        String status,
        Instant scheduledFor,
        Instant startedAt,
        Instant finishedAt,
        String sessionId,
        String output,
        String errorMessage,
        Instant cancelRequestedAt
) {
    public static AutomationRunDto from(AutomationRun run) {
        return new AutomationRunDto(
                run.id(), run.taskId(), run.taskRevision(), run.triggerType(), run.triggerId(),
                run.status(), run.scheduledFor(),
                run.startedAt(), run.finishedAt(), run.sessionId(), run.output(), run.errorMessage(),
                run.cancelRequestedAt()
        );
    }
}

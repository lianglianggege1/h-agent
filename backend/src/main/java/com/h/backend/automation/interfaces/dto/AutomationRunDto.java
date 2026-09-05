package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationRun;

import java.time.Instant;

public record AutomationRunDto(
        String id,
        String taskId,
        String triggerType,
        String status,
        Instant scheduledFor,
        Instant startedAt,
        Instant finishedAt,
        String sessionId,
        String output,
        String errorMessage
) {
    public static AutomationRunDto from(AutomationRun run) {
        return new AutomationRunDto(
                run.id(), run.taskId(), run.triggerType(), run.status(), run.scheduledFor(),
                run.startedAt(), run.finishedAt(), run.sessionId(), run.output(), run.errorMessage()
        );
    }
}

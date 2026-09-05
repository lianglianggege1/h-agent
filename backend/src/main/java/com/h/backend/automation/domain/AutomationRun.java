package com.h.backend.automation.domain;

import java.time.Instant;

public record AutomationRun(
        String id,
        String taskId,
        Long userId,
        String triggerType,
        String status,
        Instant scheduledFor,
        Instant startedAt,
        Instant finishedAt,
        String sessionId,
        String output,
        String errorMessage
) {
}

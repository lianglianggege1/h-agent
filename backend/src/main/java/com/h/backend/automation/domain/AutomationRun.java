package com.h.backend.automation.domain;

import java.time.Instant;

public record AutomationRun(
        String id,
        String taskId,
        Long userId,
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
        Instant cancelRequestedAt,
        String specSnapshot
) {
    public AutomationRun(
            String id, String taskId, Long userId, long taskRevision, String triggerType,
            String triggerId, String status, Instant scheduledFor, Instant startedAt,
            Instant finishedAt, String sessionId, String output, String errorMessage
    ) {
        this(id, taskId, userId, taskRevision, triggerType, triggerId, status, scheduledFor,
                startedAt, finishedAt, sessionId, output, errorMessage, null, null);
    }
}

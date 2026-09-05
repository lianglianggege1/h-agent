package com.h.backend.automation.domain;

import java.time.Instant;

public record AutomationTask(
        String id,
        Long userId,
        String name,
        String instruction,
        String agentId,
        AutomationRuntime runtime,
        AutomationSchedule schedule,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastStatus,
        String createdVia,
        long revision,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationTask;

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
        Instant createdAt,
        Instant updatedAt
) {
    public static AutomationTaskDto from(AutomationTask task) {
        return new AutomationTaskDto(
                task.id(), task.name(), task.instruction(), task.agentId(), task.runtime().name(),
                task.schedule().cronExpression(), task.schedule().zoneId(), task.enabled(),
                task.nextRunAt(), task.lastRunAt(), task.lastStatus(), task.createdVia(),
                task.revision(), task.createdAt(), task.updatedAt()
        );
    }
}

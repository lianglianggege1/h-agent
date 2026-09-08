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
        Instant updatedAt,
        String deliverySink,
        String deliverySessionId
) {
    public AutomationTask {
        deliverySink = deliverySink == null || deliverySink.isBlank()
                ? AutomationDeliverySink.NONE.name() : deliverySink;
    }

    /** 兼容旧调用方的构造器：默认 NONE 投递。 */
    public AutomationTask(
            String id, Long userId, String name, String instruction, String agentId,
            AutomationRuntime runtime, AutomationSchedule schedule, boolean enabled,
            Instant nextRunAt, Instant lastRunAt, String lastStatus, String createdVia,
            long revision, Instant createdAt, Instant updatedAt
    ) {
        this(id, userId, name, instruction, agentId, runtime, schedule, enabled,
                nextRunAt, lastRunAt, lastStatus, createdVia, revision, createdAt, updatedAt,
                AutomationDeliverySink.NONE.name(), null);
    }
}

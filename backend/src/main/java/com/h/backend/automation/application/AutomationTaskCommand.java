package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRuntime;

public record AutomationTaskCommand(
        String name,
        String instruction,
        String agentId,
        AutomationRuntime runtime,
        String cronExpression,
        String zoneId,
        Boolean enabled,
        String deliverySink,
        String deliverySessionId
) {
    public AutomationTaskCommand(
            String name, String instruction, String agentId, AutomationRuntime runtime,
            String cronExpression, String zoneId, Boolean enabled
    ) {
        this(name, instruction, agentId, runtime, cronExpression, zoneId, enabled, null, null);
    }
}

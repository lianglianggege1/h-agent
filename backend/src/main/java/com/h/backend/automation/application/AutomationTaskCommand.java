package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRuntime;

public record AutomationTaskCommand(
        String name,
        String instruction,
        String agentId,
        AutomationRuntime runtime,
        String cronExpression,
        String zoneId,
        Boolean enabled
) {
}

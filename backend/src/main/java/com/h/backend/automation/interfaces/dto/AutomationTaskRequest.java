package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationRuntime;

public record AutomationTaskRequest(
        String name,
        String instruction,
        String agentId,
        AutomationRuntime runtime,
        String cronExpression,
        String zoneId,
        Boolean enabled,
        Long expectedRevision
) {
}

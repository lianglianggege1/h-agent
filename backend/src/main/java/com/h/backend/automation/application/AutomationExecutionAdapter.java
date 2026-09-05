package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationTask;

public interface AutomationExecutionAdapter {
    AutomationRuntime runtime();
    AutomationExecutionResult execute(AutomationTask task);

    record AutomationExecutionResult(String sessionId, String output) {
    }
}

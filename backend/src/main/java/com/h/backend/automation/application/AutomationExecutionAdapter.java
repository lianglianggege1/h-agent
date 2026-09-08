package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.ExecutionSpec;

public interface AutomationExecutionAdapter {
    AutomationRuntime runtime();
    AutomationExecutionResult execute(ExecutionSpec spec);

    record AutomationExecutionResult(String sessionId, String output) {
    }
}

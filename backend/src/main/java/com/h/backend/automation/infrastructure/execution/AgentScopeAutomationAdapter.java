package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.ExecutionSpec;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeAutomationAdapter implements AutomationExecutionAdapter {

    private final ChatBackedAutomationRunner runner;

    public AgentScopeAutomationAdapter(ChatBackedAutomationRunner runner) {
        this.runner = runner;
    }

    @Override
    public AutomationRuntime runtime() {
        return AutomationRuntime.AGENTSCOPE;
    }

    @Override
    public AutomationExecutionResult execute(ExecutionSpec spec) {
        if (!runtime().name().equals(spec.runtime())) {
            throw new IllegalArgumentException("AgentScope adapter cannot execute " + spec.runtime());
        }
        return runner.run(spec);
    }
}

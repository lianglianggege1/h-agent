package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationTask;
import org.springframework.stereotype.Component;

@Component
public class LangChain4jAutomationAdapter implements AutomationExecutionAdapter {

    private final ChatBackedAutomationRunner runner;

    public LangChain4jAutomationAdapter(ChatBackedAutomationRunner runner) {
        this.runner = runner;
    }

    @Override
    public AutomationRuntime runtime() {
        return AutomationRuntime.LANGCHAIN4J;
    }

    @Override
    public AutomationExecutionResult execute(AutomationTask task) {
        if (task.runtime() != runtime()) {
            throw new IllegalArgumentException("LangChain4j adapter cannot execute " + task.runtime());
        }
        return runner.run(task, null);
    }
}

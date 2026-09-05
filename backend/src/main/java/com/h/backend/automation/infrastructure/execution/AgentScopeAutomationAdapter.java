package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.approval.ApprovalMode;
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
    public AutomationExecutionResult execute(AutomationTask task) {
        if (task.runtime() != runtime()) {
            throw new IllegalArgumentException("AgentScope adapter cannot execute " + task.runtime());
        }
        // 无人值守执行不能进入审批悬停；任务创建本身仍由用户显式授权。
        return runner.run(task, ApprovalMode.BYPASS);
    }
}

package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.ExecutionSpec;
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
    public AutomationExecutionResult execute(ExecutionSpec spec) {
        if (!runtime().name().equals(spec.runtime())) {
            throw new IllegalArgumentException("AgentScope adapter cannot execute " + spec.runtime());
        }
        // 无人值守执行不能悬停等待审批，也不能因“自动化”获得绕过权限。
        // DONT_ASK 会让需要人工批准的工具直接拒绝，本次 Run 终结为 REJECTED_POLICY。
        return runner.run(spec, ApprovalMode.DONT_ASK);
    }
}

package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentScopeAutomationAdapterTest {

    @Test
    void unattendedRunNeverBypassesToolApproval() {
        RecordingRunner runner = new RecordingRunner();
        AgentScopeAutomationAdapter adapter = new AgentScopeAutomationAdapter(runner);

        AutomationTask task = task();
        adapter.execute(ExecutionSpec.freeze(task, "run-1", "MANUAL", task.nextRunAt(), 900, task.createdAt()));

        assertEquals(ApprovalMode.DONT_ASK, runner.approvalMode);
    }

    private static AutomationTask task() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        return new AutomationTask(
                "task-1", 7L, "复盘", "复盘项目", "harness-agent", AutomationRuntime.AGENTSCOPE,
                new AutomationSchedule("0 0 9 * * *", "Asia/Shanghai"), true,
                now, null, null, "UI", 1L, now, now
        );
    }

    private static final class RecordingRunner extends ChatBackedAutomationRunner {
        private ApprovalMode approvalMode;

        private RecordingRunner() {
            super(null, null, new AutomationProperties(),
                    new AutomationExecutionSessionRegistry(new ChatMemoryIdFactory()));
        }

        @Override
        public AutomationExecutionAdapter.AutomationExecutionResult run(
                ExecutionSpec spec,
                ApprovalMode approvalMode
        ) {
            this.approvalMode = approvalMode;
            return new AutomationExecutionAdapter.AutomationExecutionResult("session-1", "done");
        }
    }
}

package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.domain.ExecutionSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentScopeAutomationAdapterTest {

    @Test
    void automationDelegatesTheBoundSessionWithoutAnApprovalOverride() {
        RecordingRunner runner = new RecordingRunner();
        AgentScopeAutomationAdapter adapter = new AgentScopeAutomationAdapter(runner);

        AutomationTask task = task();
        adapter.execute(ExecutionSpec.freeze(task, "run-1", "MANUAL", task.nextRunAt(), 900, task.createdAt()));

        assertEquals("session-1", runner.sessionId);
    }

    private static AutomationTask task() {
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        return new AutomationTask(
                "task-1", 7L, "复盘", "复盘项目", "harness-agent", AutomationRuntime.AGENTSCOPE,
                new AutomationSchedule("0 0 9 * * *", "Asia/Shanghai"), true,
                now, null, null, "UI", 1L, now, now,
                "SESSION", "session-1", "session-1"
        );
    }

    private static final class RecordingRunner extends ChatBackedAutomationRunner {
        private String sessionId;

        private RecordingRunner() {
            super(null, null, new AutomationProperties(), null);
        }

        @Override
        public AutomationExecutionAdapter.AutomationExecutionResult run(ExecutionSpec spec) {
            this.sessionId = spec.sessionId();
            return new AutomationExecutionAdapter.AutomationExecutionResult("session-1", "done");
        }
    }
}

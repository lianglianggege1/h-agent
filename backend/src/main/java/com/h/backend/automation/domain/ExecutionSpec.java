package com.h.backend.automation.domain;

import java.time.Instant;

/**
 * 接纳时冻结的执行规格。已接纳 Run 始终按快照执行与投递：
 * 任务后续编辑（指令、Agent、投递目标）不影响在途 Run。
 */
public record ExecutionSpec(
        String taskId,
        Long userId,
        String taskName,
        long taskRevision,
        String runId,
        String triggerType,
        Instant scheduledFor,
        String instruction,
        String agentId,
        String runtime,
        String deliverySink,
        String deliverySessionId,
        long timeoutSeconds,
        String overlapPolicy,
        String misfirePolicy,
        Instant frozenAt
) {
    public static ExecutionSpec freeze(
            AutomationTask task,
            String runId,
            String triggerType,
            Instant scheduledFor,
            long timeoutSeconds,
            Instant frozenAt
    ) {
        return new ExecutionSpec(
                task.id(), task.userId(), task.name(), task.revision(), runId, triggerType, scheduledFor,
                task.instruction(), task.agentId(), task.runtime().name(),
                task.deliverySink(), task.deliverySessionId(),
                timeoutSeconds, "SKIP", "SKIP", frozenAt
        );
    }
}

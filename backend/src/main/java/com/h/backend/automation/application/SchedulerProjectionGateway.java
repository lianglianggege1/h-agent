package com.h.backend.automation.application;

/**
 * 将平台中的调度期望状态收敛到外部调度器。
 * 调用方不需要了解 XXL-Job 的登录态、表单接口或重复 Job 修复规则。
 */
public interface SchedulerProjectionGateway {

    ProjectionResult converge(ProjectionCommand command);

    enum DesiredState {
        ACTIVE,
        STOPPED,
        DELETED
    }

    record ProjectionCommand(
            String taskId,
            long taskRevision,
            DesiredState desiredState,
            Long knownJobId,
            String taskName,
            String cronExpression,
            String zoneId
    ) {
    }

    record ProjectionResult(Long jobId) {
    }
}

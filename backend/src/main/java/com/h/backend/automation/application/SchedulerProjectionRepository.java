package com.h.backend.automation.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SchedulerProjectionRepository {

    List<SchedulerProjectionWork> claim(Instant now, int limit, String leaseOwner, Duration leaseDuration);

    /** 当前事务内取得任务级 fence；任务状态变更也使用同一把锁。 */
    default void lockTask(String taskId) {
    }

    boolean isCurrent(SchedulerProjectionWork item);

    void complete(SchedulerProjectionWork item, Long jobId, Instant now);

    void discard(SchedulerProjectionWork item, Instant now);

    void retry(SchedulerProjectionWork item, String errorMessage, Instant availableAt, Instant now);

    default Optional<SchedulerProjectionStatus> findStatus(String taskId) {
        return Optional.empty();
    }
}

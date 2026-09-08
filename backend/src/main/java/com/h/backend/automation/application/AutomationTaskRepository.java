package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationTask;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationTaskRepository {
    AutomationTask insert(AutomationTask task);
    AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement);
    AutomationTask enableOwned(Long userId, String taskId, long expectedRevision,
                               Instant nextRunAt, Instant updatedAt, int maxEnabled);
    AutomationTask disableOwned(Long userId, String taskId, long expectedRevision, Instant updatedAt);
    Optional<AutomationTask> findOwned(Long userId, String taskId);
    Optional<AutomationTask> findById(String taskId);
    List<AutomationTask> listOwned(Long userId);
    boolean softDeleteOwned(Long userId, String taskId);

    /** 本地调度器领取到期任务（SKIP LOCKED 租约）。 */
    List<AutomationTask> claimDueTasks(Instant now, int limit, String leaseOwner, Duration leaseDuration);

    /** XXL 启用时，本地扫描只领取 XXL 无法表达时区语义的任务。 */
    default List<AutomationTask> claimDueTasks(
            Instant now, int limit, String leaseOwner, Duration leaseDuration, String excludedZoneId
    ) {
        return claimDueTasks(now, limit, leaseOwner, leaseDuration);
    }

    /** 放弃租约（不改变 next_run_at），用于异常路径。 */
    void releaseLease(String taskId, String leaseOwner);

    /** 正常处理完一个租约：清空租约并把 next_run_at 推进到下一个日程点。 */
    void advanceLeaseToNextRun(String taskId, String leaseOwner, Instant nextRunAt, Instant now);

    /** Run 终态后回写任务最近运行状态（手动与调度共用）。 */
    void recordRunResult(String taskId, Instant at, String status);

    AutomationRun insertRun(AutomationRun run);

    /** 手动运行的原子接纳：已有活跃 Run 时返回 null。 */
    AutomationRun insertManualRunIfNoActive(AutomationRun run);

    /** 调度触发的幂等接纳：唯一键冲突返回 null。 */
    AutomationRun insertScheduledRunIfAbsent(AutomationRun run);

    /** 同一任务存在未终结 Run（QUEUED / RUNNING / CANCEL_REQUESTED）时返回 true。 */
    boolean existsActiveRun(String taskId);

    /** 持久化执行队列：只有一个实例能把指定 Run 从 QUEUED 原子领取为 RUNNING。 */
    default Optional<AutomationRun> claimQueuedRun(String runId, Instant startedAt) {
        return Optional.empty();
    }

    /** 返回待分发 Run ID；领取动作另行 CAS，允许多实例安全扫描。 */
    default List<String> listQueuedRunIds(int limit) {
        return List.of();
    }

    Optional<AutomationRun> findRunOwned(Long userId, String runId);

    /** 请求取消：QUEUED 直接取消，RUNNING 流转为 CANCEL_REQUESTED。 */
    Optional<AutomationRun> requestCancelRun(String runId, Instant now);

    List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit);

    /** 崩溃恢复：扫描超过执行截止时间仍未终结的 Run。 */
    default List<AutomationRun> listActiveRunsStartedBefore(Instant before, int limit) {
        return List.of();
    }
}

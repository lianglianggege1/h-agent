package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationTask;

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

    /** 手动 Run 被 XXL 接收后，将本地请求事件绑定到 XXL 的执行日志 ID。 */
    default Optional<AutomationRun> bindXxlTrigger(String runId, String triggerId) {
        return Optional.empty();
    }

    /** 按 XXL 执行身份查找已接纳的逻辑执行事件，用于回调重放幂等。 */
    default Optional<AutomationRun> findRunByTriggerId(String triggerId) {
        return Optional.empty();
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

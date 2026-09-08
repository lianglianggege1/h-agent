package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDelivery;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRunStatus;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.automation.infrastructure.execution.AutomationExecutionTimeoutException;
import com.h.backend.automation.infrastructure.execution.AutomationPolicyRejectionException;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.h.backend.automation.infrastructure.execution.AutomationWorkerPool;
import com.h.backend.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Run 协调器：调度扫描与 XXL 触发都只负责“发现 + 接纳”，实际执行异步排队。
 * Run 状态机：QUEUED → RUNNING/CANCEL_REQUESTED → 终态；执行队列以数据库为准，进程重启可续领。
 * 未接纳的触发不落 Run 行，状态体现在接纳结果（SKIPPED_*）。
 */
@Service
public class AutomationRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunCoordinator.class);
    /** misfire 跳过时任务最近运行状态（不是 Run 状态，仅用于管理页展示）。 */
    private static final String TASK_MISFIRE_STATUS = "SKIPPED_MISFIRE";
    private static final String LEASE_OWNER =
            ProcessHandle.current().pid() + "-automation-scheduler-" + UUID.randomUUID();

    private final AutomationTaskRepository repository;
    private final AutomationDeliveryRepository deliveryRepository;
    private final AutomationTaskService taskService;
    private final RunAdmissionModule admissionModule;
    private final DeliveryModule deliveryModule;
    private final Map<String, AutomationExecutionAdapter> adapters;
    private final AutomationWorkerPool workerPool;
    private final AutomationProperties properties;
    private final Clock clock;
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AutomationRunCoordinator(
            AutomationTaskRepository repository,
            AutomationDeliveryRepository deliveryRepository,
            AutomationTaskService taskService,
            RunAdmissionModule admissionModule,
            DeliveryModule deliveryModule,
            List<AutomationExecutionAdapter> adapters,
            AutomationWorkerPool workerPool,
            AutomationProperties properties
    ) {
        this(repository, deliveryRepository, taskService, admissionModule, deliveryModule,
                adapters, workerPool, properties, Clock.systemUTC());
    }

    AutomationRunCoordinator(
            AutomationTaskRepository repository,
            AutomationDeliveryRepository deliveryRepository,
            AutomationTaskService taskService,
            RunAdmissionModule admissionModule,
            DeliveryModule deliveryModule,
            List<AutomationExecutionAdapter> adapters,
            AutomationWorkerPool workerPool,
            AutomationProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.deliveryRepository = deliveryRepository;
        this.taskService = taskService;
        this.admissionModule = admissionModule;
        this.deliveryModule = deliveryModule;
        this.adapters = new HashMap<>();
        for (AutomationExecutionAdapter adapter : adapters) {
            this.adapters.put(adapter.runtime().name(), adapter);
        }
        this.workerPool = workerPool;
        this.properties = properties;
        this.clock = clock;
    }

    public AutomationRun runNow(Long userId, String taskId) {
        AutomationTask task = taskService.requireOwned(userId, taskId);
        if (!task.enabled()) {
            throw new BusinessException(40940, "任务已关闭，请先开启后再手动运行");
        }
        if (repository.existsActiveRun(taskId)) {
            throw new BusinessException(40941, "上一次运行还未结束，请稍后再试");
        }
        Instant now = clock.instant();
        String runId = UUID.randomUUID().toString();
        AutomationRun run = new AutomationRun(
                runId, task.id(), task.userId(), task.revision(), "MANUAL", "manual:" + runId,
                AutomationRunStatus.QUEUED.name(), now, now, null, null, null, null,
                null, admissionModule.snapshot(task, runId, "MANUAL", now)
        );
        AutomationRun inserted = repository.insertManualRunIfNoActive(run);
        if (inserted == null) {
            throw new BusinessException(40941, "上一次运行还未结束，请稍后再试");
        }
        submitExecution(inserted);
        return inserted;
    }

    public AutomationRun requestCancel(Long userId, String runId) {
        AutomationRun run = repository.findRunOwned(userId, runId)
                .orElseThrow(() -> new BusinessException(40404, "运行记录不存在"));
        if (AutomationRunStatus.isTerminal(run.status())) {
            return run;
        }
        Instant now = clock.instant();
        AutomationRun updated = repository.requestCancelRun(runId, now).orElse(run);
        Future<?> future = runningFutures.get(runId);
        if (future != null) {
            future.cancel(true);
        }
        return updated;
    }

    /** 本地调度器周期入口：领取到期任务 → 归一化/重叠校验 → 接纳 → 异步执行。 */
    public int pollDueTasks() {
        Instant now = clock.instant();
        String xxlManagedZone = properties.getXxlJob().isEnabled()
                ? properties.getXxlJob().getSchedulerZoneId() : null;
        List<AutomationTask> dueTasks = repository.claimDueTasks(
                now, properties.getBatchSize(), LEASE_OWNER, properties.getLeaseDuration(), xxlManagedZone
        );
        for (AutomationTask task : dueTasks) {
            try {
                handleLeasedTask(task, now);
            } catch (RuntimeException error) {
                log.error("Automation scheduled task failed taskId={}", task.id(), error);
                repository.releaseLease(task.id(), LEASE_OWNER);
            }
        }
        return dueTasks.size();
    }

    /**
     * 进程在接纳后崩溃时，内存 Future 会丢失。超过冻结的全局执行上限后将遗留 Run
     * 收敛为 TIMED_OUT；终态 CAS 保证多个实例或迟到的原执行者只有一个能完成投递建单。
     */
    public int recoverTimedOutRuns() {
        Instant now = clock.instant();
        Instant deadline = now.minus(properties.getExecutionTimeout()).minusSeconds(30);
        List<AutomationRun> stale = repository.listActiveRunsStartedBefore(deadline, properties.getBatchSize());
        for (AutomationRun run : stale) {
            ExecutionSpec spec = null;
            String message = "自动化执行进程中断或超过超时上限，已由恢复扫描终止";
            try {
                spec = admissionModule.readSnapshot(run);
            } catch (RuntimeException invalidSnapshot) {
                message = invalidSnapshot.getMessage();
            }
            finishRun(spec, run, AutomationRunStatus.TIMED_OUT.name(), null, null, message,
                    run.cancelRequestedAt());
        }
        return stale.size();
    }

    /** 扫描持久化队列。多实例可能看到同一 ID，但 QUEUED → RUNNING 的 CAS 只允许一个执行者。 */
    public int dispatchQueuedRuns() {
        List<String> queuedRunIds = repository.listQueuedRunIds(properties.getBatchSize());
        queuedRunIds.forEach(this::submitExecution);
        return queuedRunIds.size();
    }

    /** XXL-Job 执行器回调入口：按任务 ID + 版本接纳一次 Run，返回接纳结果。 */
    public RunAdmissionModule.AdmissionStatus submitScheduled(
            String taskId,
            long taskRevision,
            Instant observedTriggerAt,
            String triggerId
    ) {
        RunAdmissionModule.AdmissionResult result =
                admissionModule.admitScheduled(taskId, taskRevision, observedTriggerAt, triggerId);
        result.acceptedRun().ifPresent(this::submitExecution);
        return result.status();
    }

    private void handleLeasedTask(AutomationTask task, Instant now) {
        Instant dueAt = task.nextRunAt();
        if (dueAt == null) {
            repository.releaseLease(task.id(), LEASE_OWNER);
            return;
        }
        Instant nextRun = task.schedule().nextAfter(now);
        if (now.isAfter(dueAt.plus(properties.getMisfireTolerance()))) {
            log.info("Automation task misfired beyond tolerance, advancing taskId={} dueAt={}", task.id(), dueAt);
            repository.recordRunResult(task.id(), now, TASK_MISFIRE_STATUS);
            repository.advanceLeaseToNextRun(task.id(), LEASE_OWNER, nextRun, now);
            return;
        }
        if (repository.existsActiveRun(task.id())) {
            // 重叠策略 SKIP：推进到下一个日程点，本次不落 Run。
            repository.advanceLeaseToNextRun(task.id(), LEASE_OWNER, nextRun, now);
            return;
        }
        String runId = UUID.randomUUID().toString();
        AutomationRun run = new AutomationRun(
                runId, task.id(), task.userId(), task.revision(), "SCHEDULED",
                "scheduler:" + dueAt.toEpochMilli() + ":" + runId,
                AutomationRunStatus.QUEUED.name(), dueAt, now, null, null, null, null,
                null, admissionModule.snapshot(task, runId, "SCHEDULED", dueAt)
        );
        AutomationRun inserted = repository.insertScheduledRunIfAbsent(run);
        // 接纳成功即推进租约，执行时长不占用调度租约。
        repository.advanceLeaseToNextRun(task.id(), LEASE_OWNER, nextRun, now);
        if (inserted != null) {
            submitExecution(inserted.id());
        }
    }

    private void submitExecution(AutomationRun run) {
        submitExecution(run.id());
    }

    private void submitExecution(String runId) {
        FutureTask<Void> work = new FutureTask<>(() -> {
            try {
                repository.claimQueuedRun(runId, clock.instant()).ifPresent(this::execute);
            } finally {
                runningFutures.remove(runId);
            }
            return null;
        });
        if (runningFutures.putIfAbsent(runId, work) != null) {
            return;
        }
        try {
            workerPool.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            runningFutures.remove(runId, work);
            // Run 仍保持 QUEUED，周期分发器会在有容量时重试，HTTP/XXL 回调无需失败重放。
            log.warn("Automation worker pool saturated; run remains queued runId={}", runId);
        }
    }

    private void execute(AutomationRun run) {
        AutomationExecutionAdapter.AutomationExecutionResult result = null;
        String status = AutomationRunStatus.FAILED.name();
        String errorMessage = null;
        ExecutionSpec spec = null;
        try {
            spec = admissionModule.readSnapshot(run);
            taskService.assertAgentRunnable(spec.agentId());
            AutomationExecutionAdapter adapter = adapters.get(spec.runtime());
            if (adapter == null) {
                throw new AutomationPolicyRejectionException("没有可用执行器：" + spec.runtime());
            }
            result = adapter.execute(spec);
            status = AutomationRunStatus.SUCCEEDED.name();
        } catch (AutomationPolicyRejectionException error) {
            status = AutomationRunStatus.REJECTED_POLICY.name();
            errorMessage = error.getMessage();
            log.warn("Automation run rejected by policy runId={} taskId={}: {}", run.id(), run.taskId(), error.getMessage());
        } catch (AutomationExecutionTimeoutException error) {
            status = AutomationRunStatus.TIMED_OUT.name();
            errorMessage = error.getMessage();
            log.warn("Automation run timed out runId={} taskId={}", run.id(), run.taskId());
        } catch (BusinessException error) {
            status = AutomationRunStatus.REJECTED_POLICY.name();
            errorMessage = error.getMessage();
            log.warn("Automation run policy check failed runId={} taskId={}: {}", run.id(), run.taskId(), error.getMessage());
        } catch (Exception error) {
            status = AutomationRunStatus.FAILED.name();
            errorMessage = error.getMessage() == null ? "Agent 执行失败" : error.getMessage();
            log.error("Automation run failed runId={} taskId={}", run.id(), run.taskId(), error);
        }
        // 终态前尊重协作式取消：取消请求优先于失败/超时归类；成功则保持 SUCCEEDED。
        AutomationRun latest = repository.findRunOwned(run.userId(), run.id()).orElse(run);
        Instant cancelRequestedAt = latest.cancelRequestedAt();
        if (cancelRequestedAt != null && !AutomationRunStatus.SUCCEEDED.name().equals(status)) {
            status = AutomationRunStatus.CANCELLED.name();
        }
        finishRun(spec, run, status,
                result == null ? null : result.sessionId(),
                result == null ? null : result.output(),
                errorMessage, cancelRequestedAt);
    }

    private void finishRun(
            ExecutionSpec spec,
            AutomationRun run,
            String status,
            String sessionId,
            String output,
            String errorMessage,
            Instant cancelRequestedAt
    ) {
        Instant now = clock.instant();
        List<AutomationDelivery> deliveries;
        try {
            deliveries = spec == null ? List.of() : deliveryModule.planTerminalDeliveries(
                    spec, run, status, sessionId, output, errorMessage, now);
        } catch (RuntimeException error) {
            log.error("Planning deliveries failed runId={}", run.id(), error);
            deliveries = List.of();
        }
        boolean terminal = deliveryRepository.completeRunAndCreateDeliveries(
                run.id(), status, now, sessionId,
                truncate(output, 20_000), truncate(errorMessage, 2_000),
                cancelRequestedAt, deliveries
        );
        if (!terminal) {
            log.info("Automation run already terminal, skip finalization runId={}", run.id());
        } else {
            repository.recordRunResult(run.taskId(), now, status);
        }
        runningFutures.remove(run.id());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

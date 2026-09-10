package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDelivery;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRunStatus;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.automation.infrastructure.execution.AutomationExecutionTimeoutException;
import com.h.backend.automation.infrastructure.execution.AutomationPolicyRejectionException;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.h.backend.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动化执行协调器。XXL-Job 是唯一执行入口：计划触发与立即运行最终都进入
 * {@link #executeXxl}，并由该调用同步等待 Agent 的真实终态。
 */
@Service
public class AutomationRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunCoordinator.class);

    private final AutomationTaskRepository repository;
    private final AutomationDeliveryRepository deliveryRepository;
    private final AutomationTaskService taskService;
    private final RunAdmissionModule admissionModule;
    private final DeliveryModule deliveryModule;
    private final Map<String, AutomationExecutionAdapter> adapters;
    private final AutomationProperties properties;
    private final SchedulerProjectionRepository projectionRepository;
    private final SchedulerProjectionGateway schedulerGateway;
    private final Clock clock;
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();

    public AutomationRunCoordinator(
            AutomationTaskRepository repository,
            AutomationDeliveryRepository deliveryRepository,
            AutomationTaskService taskService,
            RunAdmissionModule admissionModule,
            DeliveryModule deliveryModule,
            List<AutomationExecutionAdapter> adapters,
            AutomationProperties properties,
            SchedulerProjectionRepository projectionRepository,
            ObjectProvider<SchedulerProjectionGateway> schedulerGateway
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
        this.properties = properties;
        this.projectionRepository = projectionRepository;
        this.schedulerGateway = schedulerGateway.getIfAvailable();
        this.clock = Clock.systemUTC();
    }

    /** 创建本地执行事实，再让 XXL-Job 调度这个事实。此方法不直接运行 Agent。 */
    public AutomationRun runNow(Long userId, String taskId) {
        AutomationTask task = taskService.requireOwned(userId, taskId);
        if (!task.enabled()) {
            throw new BusinessException(40940, "任务已关闭，请先开启后再手动运行");
        }
        SchedulerProjectionStatus projection = requireRunnableProjection(task);
        if (repository.existsActiveRun(taskId)) {
            throw new BusinessException(40941, "上一次运行还未结束，请稍后再试");
        }

        Instant now = clock.instant();
        String runId = UUID.randomUUID().toString();
        AutomationRun requested = new AutomationRun(
                runId, task.id(), task.userId(), task.revision(), "MANUAL", "manual-request:" + runId,
                AutomationRunStatus.QUEUED.name(), now, now, null, null, null, null,
                null, admissionModule.snapshot(task, runId, "MANUAL", now)
        );
        AutomationRun inserted = repository.insertManualRunIfNoActive(requested);
        if (inserted == null) {
            throw new BusinessException(40941, "上一次运行还未结束，请稍后再试");
        }
        try {
            schedulerGateway.trigger(new SchedulerProjectionGateway.TriggerCommand(
                    projection.xxlJobId(), task.id(), task.revision(), runId));
            return inserted;
        } catch (RuntimeException error) {
            String message = "XXL-Job 触发失败：" + safeMessage(error);
            AutomationRun running = repository.claimQueuedRun(runId, clock.instant()).orElse(inserted);
            ExecutionSpec spec = admissionModule.readSnapshot(running);
            finishRun(spec, running, AutomationRunStatus.FAILED.name(), null, null, message, null);
            return repository.findRunOwned(userId, runId).orElse(running);
        }
    }

    public AutomationRun requestCancel(Long userId, String runId) {
        AutomationRun run = repository.findRunOwned(userId, runId)
                .orElseThrow(() -> new BusinessException(40404, "运行记录不存在"));
        if (AutomationRunStatus.isTerminal(run.status())) {
            return run;
        }
        AutomationRun updated = repository.requestCancelRun(runId, clock.instant()).orElse(run);
        Thread thread = runningThreads.get(runId);
        if (thread != null) {
            thread.interrupt();
        }
        return updated;
    }

    /** XXL handler 的同步入口；返回时本地 Run 已经处于真实终态。 */
    public XxlExecutionResult executeXxl(
            String taskId,
            long taskRevision,
            String triggerType,
            String requestedRunId,
            Instant observedTriggerAt,
            String xxlTriggerId
    ) {
        AutomationRun queued;
        if ("MANUAL".equals(triggerType)) {
            queued = requireManualRun(taskId, taskRevision, requestedRunId, xxlTriggerId);
        } else {
            RunAdmissionModule.AdmissionResult admission = admissionModule.admitScheduledOrAdminManual(
                    taskId, taskRevision, observedTriggerAt, xxlTriggerId);
            if (admission.status() != RunAdmissionModule.AdmissionStatus.ACCEPTED) {
                return XxlExecutionResult.skipped(admission.status().name());
            }
            queued = admission.run();
        }

        AutomationRun running = repository.claimQueuedRun(queued.id(), clock.instant()).orElse(null);
        if (running == null) {
            AutomationRun existing = repository.findRunOwned(queued.userId(), queued.id()).orElse(queued);
            if (AutomationRunStatus.isTerminal(existing.status())) {
                return new XxlExecutionResult(true, existing.id(), existing.status(), existing.errorMessage());
            }
            return XxlExecutionResult.skipped("SKIPPED_ALREADY_CLAIMED");
        }

        runningThreads.put(running.id(), Thread.currentThread());
        try {
            return execute(running);
        } finally {
            runningThreads.remove(running.id(), Thread.currentThread());
        }
    }

    /** 将执行器进程中断留下的 Run 收敛到超时终态；不会绕过 XXL 重新执行。 */
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

    private SchedulerProjectionStatus requireRunnableProjection(AutomationTask task) {
        if (schedulerGateway == null || !properties.getXxlJob().isEnabled()) {
            throw new BusinessException(50340, "XXL-Job 未启用，自动化任务无法执行");
        }
        SchedulerProjectionStatus status = projectionRepository.findStatus(task.id())
                .orElseThrow(() -> new BusinessException(40942, "任务尚未同步到 XXL-Job，请稍后再试"));
        if (status.xxlJobId() == null || status.syncedRevision() == null
                || status.syncedRevision() != task.revision()
                || !"SYNCED".equals(status.syncStatus())
                || !"ACTIVE".equals(status.desiredState())) {
            throw new BusinessException(40942, "任务尚未同步到 XXL-Job，请稍后再试");
        }
        return status;
    }

    private AutomationRun requireManualRun(
            String taskId, long taskRevision, String runId, String xxlTriggerId
    ) {
        if (runId == null || runId.isBlank()) {
            throw new AutomationPolicyRejectionException("手动触发缺少本地 Run ID");
        }
        AutomationTask task = repository.findById(taskId)
                .orElseThrow(() -> new AutomationPolicyRejectionException("自动化任务不存在"));
        AutomationRun run = repository.findRunOwned(task.userId(), runId)
                .orElseThrow(() -> new AutomationPolicyRejectionException("手动运行记录不存在"));
        if (!taskId.equals(run.taskId()) || taskRevision != run.taskRevision()
                || !"MANUAL".equals(run.triggerType())) {
            throw new AutomationPolicyRejectionException("手动触发与本地运行记录不匹配");
        }
        return repository.bindXxlTrigger(runId, xxlTriggerId).orElse(run);
    }

    private XxlExecutionResult execute(AutomationRun run) {
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
            errorMessage = safeMessage(error);
            log.warn("Automation run rejected runId={} taskId={}: {}", run.id(), run.taskId(), errorMessage);
        } catch (AutomationExecutionTimeoutException error) {
            status = AutomationRunStatus.TIMED_OUT.name();
            errorMessage = safeMessage(error);
            log.warn("Automation run timed out runId={} taskId={}", run.id(), run.taskId());
        } catch (BusinessException error) {
            status = AutomationRunStatus.REJECTED_POLICY.name();
            errorMessage = safeMessage(error);
            log.warn("Automation run policy check failed runId={} taskId={}: {}", run.id(), run.taskId(), errorMessage);
        } catch (Exception error) {
            status = AutomationRunStatus.FAILED.name();
            errorMessage = safeMessage(error);
            log.error("Automation run failed runId={} taskId={}", run.id(), run.taskId(), error);
        }

        AutomationRun latest = repository.findRunOwned(run.userId(), run.id()).orElse(run);
        Instant cancelRequestedAt = latest.cancelRequestedAt();
        if (cancelRequestedAt != null && !AutomationRunStatus.SUCCEEDED.name().equals(status)) {
            status = AutomationRunStatus.CANCELLED.name();
        }
        finishRun(spec, run, status,
                result == null ? (spec == null ? null : spec.sessionId()) : result.sessionId(),
                result == null ? null : result.output(),
                errorMessage, cancelRequestedAt);
        return new XxlExecutionResult(true, run.id(), status, errorMessage);
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
        if (terminal) {
            repository.recordRunResult(run.taskId(), now, status);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "Agent 执行失败" : error.getMessage();
    }

    public record XxlExecutionResult(boolean executed, String runId, String status, String message) {
        static XxlExecutionResult skipped(String reason) {
            return new XxlExecutionResult(false, null, reason, reason);
        }

        public boolean succeeded() {
            return AutomationRunStatus.SUCCEEDED.name().equals(status);
        }
    }
}

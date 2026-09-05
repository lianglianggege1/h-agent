package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.h.backend.automation.infrastructure.execution.AutomationWorkerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AutomationRunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AutomationRunCoordinator.class);

    private final AutomationTaskRepository repository;
    private final AutomationTaskService taskService;
    private final Map<AutomationRuntime, AutomationExecutionAdapter> adapters;
    private final AutomationWorkerPool executor;
    private final AutomationProperties properties;
    private final Clock clock;

    @Autowired
    public AutomationRunCoordinator(
            AutomationTaskRepository repository,
            AutomationTaskService taskService,
            List<AutomationExecutionAdapter> adapters,
            AutomationWorkerPool executor,
            AutomationProperties properties
    ) {
        this(repository, taskService, adapters, executor, properties, Clock.systemUTC());
    }

    AutomationRunCoordinator(
            AutomationTaskRepository repository,
            AutomationTaskService taskService,
            List<AutomationExecutionAdapter> adapters,
            AutomationWorkerPool executor,
            AutomationProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.taskService = taskService;
        this.adapters = new EnumMap<>(AutomationRuntime.class);
        for (AutomationExecutionAdapter adapter : adapters) {
            this.adapters.put(adapter.runtime(), adapter);
        }
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public AutomationRun runNow(Long userId, String taskId) {
        AutomationTask task = taskService.requireOwned(userId, taskId);
        AutomationRun run = beginRun(task, "MANUAL", null);
        executor.submit(() -> execute(task, run, null));
        return run;
    }

    public void pollDue() {
        Instant now = clock.instant();
        String leaseOwner = nodeIdentity();
        List<AutomationTask> tasks = repository.claimDue(
                now, Math.max(1, properties.getBatchSize()), leaseOwner, properties.getLeaseDuration()
        );
        for (AutomationTask task : tasks) {
            AutomationRun run = beginRun(task, "SCHEDULED", task.nextRunAt());
            try {
                executor.submit(() -> execute(task, run, leaseOwner));
            } catch (RuntimeException error) {
                failBeforeDispatch(task, run, leaseOwner, error);
            }
        }
    }

    private AutomationRun beginRun(AutomationTask task, String triggerType, Instant scheduledFor) {
        Instant now = clock.instant();
        return repository.insertRun(new AutomationRun(
                UUID.randomUUID().toString(), task.id(), task.userId(), triggerType, "RUNNING",
                scheduledFor, now, null, null, null, null
        ));
    }

    private void execute(AutomationTask task, AutomationRun run, String leaseOwner) {
        String status = "SUCCEEDED";
        String sessionId = null;
        String output = null;
        String errorMessage = null;
        try {
            AutomationExecutionAdapter adapter = adapters.get(task.runtime());
            if (adapter == null) {
                throw new IllegalStateException("未配置自动化运行时：" + task.runtime());
            }
            AutomationExecutionAdapter.AutomationExecutionResult result = adapter.execute(task);
            sessionId = result.sessionId();
            output = result.output();
        } catch (Exception error) {
            status = "FAILED";
            errorMessage = safeMessage(error);
            log.warn("Automation run failed taskId={} runId={}: {}", task.id(), run.id(), errorMessage, error);
        }

        Instant finishedAt = clock.instant();
        repository.completeRun(run.id(), status, finishedAt, sessionId, output, errorMessage);
        if (leaseOwner == null) {
            repository.recordManualRunResult(task.id(), finishedAt, status);
        } else {
            repository.releaseLease(
                    task.id(), leaseOwner, task.schedule().nextAfter(finishedAt), finishedAt, status
            );
        }
    }

    private void failBeforeDispatch(
            AutomationTask task,
            AutomationRun run,
            String leaseOwner,
            RuntimeException error
    ) {
        Instant now = clock.instant();
        repository.completeRun(run.id(), "FAILED", now, null, null, safeMessage(error));
        repository.releaseLease(task.id(), leaseOwner, task.schedule().nextAfter(now), now, "FAILED");
    }

    private static String nodeIdentity() {
        return ProcessHandle.current().pid() + "-" + UUID.randomUUID();
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "自动化任务执行失败"
                : error.getMessage();
    }
}

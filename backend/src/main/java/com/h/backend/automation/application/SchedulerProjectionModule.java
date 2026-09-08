package com.h.backend.automation.application;

import com.h.backend.automation.application.SchedulerProjectionGateway.ProjectionCommand;
import com.h.backend.automation.application.SchedulerProjectionGateway.ProjectionResult;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "automation.xxl-job", name = "enabled", havingValue = "true")
public class SchedulerProjectionModule {

    private static final Logger log = LoggerFactory.getLogger(SchedulerProjectionModule.class);

    private final SchedulerProjectionRepository repository;
    private final SchedulerProjectionGateway gateway;
    private final AutomationProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final String leaseOwner = ProcessHandle.current().pid() + "-scheduler-" + UUID.randomUUID();

    @Autowired
    public SchedulerProjectionModule(
            SchedulerProjectionRepository repository,
            SchedulerProjectionGateway gateway,
            AutomationProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this(repository, gateway, properties, Clock.systemUTC(), new TransactionTemplate(transactionManager));
    }

    SchedulerProjectionModule(
            SchedulerProjectionRepository repository,
            SchedulerProjectionGateway gateway,
            AutomationProperties properties,
            Clock clock
    ) {
        this(repository, gateway, properties, clock, null);
    }

    SchedulerProjectionModule(
            SchedulerProjectionRepository repository,
            SchedulerProjectionGateway gateway,
            AutomationProperties properties,
            Clock clock,
            TransactionTemplate transactionTemplate
    ) {
        this.repository = repository;
        this.gateway = gateway;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    public void synchronizePending() {
        Instant now = clock.instant();
        AutomationProperties.XxlJob xxlJob = properties.getXxlJob();
        for (SchedulerProjectionWork item : repository.claim(
                now, Math.max(1, xxlJob.getBatchSize()), leaseOwner, xxlJob.getLeaseDuration()
        )) {
            if (transactionTemplate == null) {
                synchronize(item);
            } else {
                transactionTemplate.executeWithoutResult(ignored -> synchronize(item));
            }
        }
    }

    private void synchronize(SchedulerProjectionWork item) {
        Instant now = clock.instant();
        // 与任务启停/编辑共用 PG advisory xact lock，并持有到远端副作用及本地 CAS 完成。
        repository.lockTask(item.taskId());
        if (!repository.isCurrent(item)) {
            repository.discard(item, now);
            return;
        }
        try {
            ProjectionResult result = gateway.converge(new ProjectionCommand(
                    item.taskId(), item.taskRevision(), item.desiredState(), item.knownJobId(),
                    item.taskName(), item.cronExpression(), item.zoneId()
            ));
            repository.complete(item, result.jobId(), clock.instant());
        } catch (RuntimeException error) {
            String message = safeMessage(error);
            Duration backoff = retryBackoff(item.attemptCount());
            repository.retry(item, message, clock.instant().plus(backoff), clock.instant());
            log.warn("XXL-Job projection failed taskId={} revision={}: {}",
                    item.taskId(), item.taskRevision(), message);
        }
    }

    private static Duration retryBackoff(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount, 0), 6);
        return Duration.ofSeconds(Math.min(300, 5L << exponent));
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "XXL-Job 调度同步失败";
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}

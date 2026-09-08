package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationRunCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 本地调度扫描；XXL 启用时只处理其单时区 Cron 无法准确表达的任务。 */
@Component
@ConditionalOnExpression("'${automation.enabled:true}' == 'true'")
public class AutomationPollingJob {

    private final AutomationRunCoordinator coordinator;

    public AutomationPollingJob(AutomationRunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${automation.polling-delay:15s}")
    public void poll() {
        coordinator.pollDueTasks();
    }
}

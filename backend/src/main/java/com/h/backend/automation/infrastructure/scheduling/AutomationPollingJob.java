package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationRunCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "automation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutomationPollingJob {

    private final AutomationRunCoordinator coordinator;

    public AutomationPollingJob(AutomationRunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${automation.polling-delay:15s}")
    public void poll() {
        coordinator.pollDue();
    }
}

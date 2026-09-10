package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.AutomationRunCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 将进程崩溃遗留的活跃 Run 收敛到可审计终态，避免永久阻塞后续日程。 */
@Component
@ConditionalOnProperty(prefix = "automation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutomationRunRecoveryJob {

    private final AutomationRunCoordinator coordinator;

    public AutomationRunRecoveryJob(AutomationRunCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${automation.recovery-delay:60s}")
    public void recover() {
        coordinator.recoverTimedOutRuns();
    }
}

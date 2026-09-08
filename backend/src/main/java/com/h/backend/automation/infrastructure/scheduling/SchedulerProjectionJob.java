package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.application.SchedulerProjectionModule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "automation.xxl-job", name = "enabled", havingValue = "true")
public class SchedulerProjectionJob {

    private final SchedulerProjectionModule module;

    public SchedulerProjectionJob(SchedulerProjectionModule module) {
        this.module = module;
    }

    @Scheduled(fixedDelayString = "${automation.xxl-job.polling-delay:5s}")
    public void synchronize() {
        module.synchronizePending();
    }
}

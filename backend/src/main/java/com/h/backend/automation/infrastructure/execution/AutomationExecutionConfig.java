package com.h.backend.automation.infrastructure.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationExecutionConfig {

    @Bean(destroyMethod = "close")
    public AutomationWorkerPool automationWorkerPool(AutomationProperties properties) {
        return new AutomationWorkerPool(Executors.newFixedThreadPool(
                Math.max(1, properties.getWorkerThreads()),
                Thread.ofPlatform().name("automation-worker-", 0).factory()
        ));
    }
}

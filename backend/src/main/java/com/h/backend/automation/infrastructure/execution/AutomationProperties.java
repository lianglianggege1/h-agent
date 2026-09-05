package com.h.backend.automation.infrastructure.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "automation")
public class AutomationProperties {
    private boolean enabled = true;
    private Duration pollingDelay = Duration.ofSeconds(15);
    private int batchSize = 10;
    private Duration leaseDuration = Duration.ofMinutes(30);
    private Duration executionTimeout = Duration.ofMinutes(20);
    private int workerThreads = 4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getPollingDelay() { return pollingDelay; }
    public void setPollingDelay(Duration pollingDelay) { this.pollingDelay = pollingDelay; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getExecutionTimeout() { return executionTimeout; }
    public void setExecutionTimeout(Duration executionTimeout) { this.executionTimeout = executionTimeout; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
}

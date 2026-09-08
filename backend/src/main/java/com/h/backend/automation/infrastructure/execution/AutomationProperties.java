package com.h.backend.automation.infrastructure.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "automation")
public class AutomationProperties {
    private boolean enabled = true;
    private Duration pollingDelay = Duration.ofSeconds(15);
    private int batchSize = 10;
    private Duration leaseDuration = Duration.ofMinutes(30);
    private Duration executionTimeout = Duration.ofMinutes(15);
    private Duration misfireTolerance = Duration.ofMinutes(10);
    private int workerThreads = 4;
    private final XxlJob xxlJob = new XxlJob();
    private final Delivery delivery = new Delivery();

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
    public Duration getMisfireTolerance() { return misfireTolerance; }
    public void setMisfireTolerance(Duration misfireTolerance) { this.misfireTolerance = misfireTolerance; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public XxlJob getXxlJob() { return xxlJob; }
    public Delivery getDelivery() { return delivery; }

    public static class Delivery {
        private int batchSize = 10;
        private Duration leaseDuration = Duration.ofMinutes(2);
        private Duration pollingDelay = Duration.ofSeconds(10);
        private int maxAttempts = 8;

        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
        public Duration getPollingDelay() { return pollingDelay; }
        public void setPollingDelay(Duration pollingDelay) { this.pollingDelay = pollingDelay; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class XxlJob {
        private boolean enabled;
        private String baseUrl = "http://127.0.0.1:8080";
        private String username;
        private String password;
        private int jobGroup = 3;
        private String author = "h-agent";
        private String executorHandler = "automationDispatchHandler";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(8);
        private Duration pollingDelay = Duration.ofSeconds(5);
        private Duration leaseDuration = Duration.ofSeconds(30);
        private int batchSize = 10;
        private String executorAppName = "h-agent-job-executor";
        private String executorAddress;
        private String executorIp;
        private int executorPort = 9999;
        private String accessToken;
        private String logPath = "./logs/xxl-job";
        private int logRetentionDays = 30;
        private String schedulerZoneId = "Asia/Shanghai";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getJobGroup() { return jobGroup; }
        public void setJobGroup(int jobGroup) { this.jobGroup = jobGroup; }
        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }
        public String getExecutorHandler() { return executorHandler; }
        public void setExecutorHandler(String executorHandler) { this.executorHandler = executorHandler; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public Duration getPollingDelay() { return pollingDelay; }
        public void setPollingDelay(Duration pollingDelay) { this.pollingDelay = pollingDelay; }
        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public String getExecutorAppName() { return executorAppName; }
        public void setExecutorAppName(String executorAppName) { this.executorAppName = executorAppName; }
        public String getExecutorAddress() { return executorAddress; }
        public void setExecutorAddress(String executorAddress) { this.executorAddress = executorAddress; }
        public String getExecutorIp() { return executorIp; }
        public void setExecutorIp(String executorIp) { this.executorIp = executorIp; }
        public int getExecutorPort() { return executorPort; }
        public void setExecutorPort(int executorPort) { this.executorPort = executorPort; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getLogPath() { return logPath; }
        public void setLogPath(String logPath) { this.logPath = logPath; }
        public int getLogRetentionDays() { return logRetentionDays; }
        public void setLogRetentionDays(int logRetentionDays) { this.logRetentionDays = logRetentionDays; }
        public String getSchedulerZoneId() { return schedulerZoneId; }
        public void setSchedulerZoneId(String schedulerZoneId) { this.schedulerZoneId = schedulerZoneId; }

        public void requireSecureConfiguration() {
            if (baseUrl == null || baseUrl.isBlank()
                    || username == null || username.isBlank()
                    || password == null || password.isBlank()) {
                throw new IllegalStateException("启用 XXL-Job 时必须配置 Admin 地址、账号和密码");
            }
        }
    }
}

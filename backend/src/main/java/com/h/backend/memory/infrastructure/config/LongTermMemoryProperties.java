package com.h.backend.memory.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "memory.long-term")
public class LongTermMemoryProperties {

    private boolean enabled = false;
    private final Mem0 mem0 = new Mem0();
    private final Recall recall = new Recall();
    private final Capture capture = new Capture();
    private final ExplicitMutation explicitMutation = new ExplicitMutation();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mem0 getMem0() {
        return mem0;
    }

    public Recall getRecall() {
        return recall;
    }

    public Capture getCapture() {
        return capture;
    }

    public ExplicitMutation getExplicitMutation() {
        return explicitMutation;
    }

    public static class Mem0 {
        private String baseUrl = "http://localhost:8888";
        private String apiKey = "";
        private String contractVersion = "";
        private String openapiSha256 = "";
        private Duration connectTimeout = Duration.ofMillis(300);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getContractVersion() {
            return contractVersion;
        }

        public void setContractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
        }

        public String getOpenapiSha256() {
            return openapiSha256;
        }

        public void setOpenapiSha256(String openapiSha256) {
            this.openapiSha256 = openapiSha256;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }
    }

    /** maxAttempts 语义是“包含第一次请求的总尝试次数”。 */
    public static class Recall {
        private Duration responseTimeout = Duration.ofMillis(900);
        private int maxAttempts = 1;
        private int topKPerScope = 4;
        private int maxTotalResults = 8;
        private int maxChars = 6000;
        private boolean circuitBreakerEnabled = true;

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getTopKPerScope() {
            return topKPerScope;
        }

        public void setTopKPerScope(int topKPerScope) {
            this.topKPerScope = topKPerScope;
        }

        public int getMaxTotalResults() {
            return maxTotalResults;
        }

        public void setMaxTotalResults(int maxTotalResults) {
            this.maxTotalResults = maxTotalResults;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }

        public boolean isCircuitBreakerEnabled() {
            return circuitBreakerEnabled;
        }

        public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
            this.circuitBreakerEnabled = circuitBreakerEnabled;
        }
    }

    public static class Capture {
        private boolean outboxEnabled = true;
        private int maxAttempts = 10;
        private Duration initialDelay = Duration.ofSeconds(5);
        private Duration maxDelay = Duration.ofMinutes(15);
        private double multiplier = 2.0;
        private double jitter = 0.2;

        public boolean isOutboxEnabled() {
            return outboxEnabled;
        }

        public void setOutboxEnabled(boolean outboxEnabled) {
            this.outboxEnabled = outboxEnabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }
    }

    public static class ExplicitMutation {
        private Duration responseTimeout = Duration.ofMillis(1500);
        private int maxAttempts = 1;

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}

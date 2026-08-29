package com.h.agent.observability;

import com.h.agent.observability.semantic.ContentCaptureMode;
import com.h.agent.observability.semantic.ContentLimits;

public record AgentObservabilityConfig(
        String enabled,
        String baseUrl,
        String publicKey,
        String secretKey,
        String environment,
        String serviceName,
        String serviceVersion,
        double rootRatio,
        ContentCaptureMode contentMode,
        ContentLimits limits,
        int queueSize,
        int batchSize,
        long scheduleDelayMillis,
        long timeoutMillis,
        long shutdownTimeoutMillis
) {

    public static final String ENABLED_AUTO = "auto";

    public static Builder builder() {
        return new Builder();
    }

    public boolean explicitlyDisabled() {
        return "false".equalsIgnoreCase(enabled);
    }

    public static final class Builder {
        private String enabled = ENABLED_AUTO;
        private String baseUrl;
        private String publicKey;
        private String secretKey;
        private String environment = "local";
        private String serviceName = "h-agent";
        private String serviceVersion = "0.0.1";
        private double rootRatio = 1.0;
        private ContentCaptureMode contentMode = ContentCaptureMode.STRUCTURED;
        private ContentLimits limits = ContentLimits.defaults();
        private int queueSize = 2048;
        private int batchSize = 512;
        private long scheduleDelayMillis = 1000;
        private long timeoutMillis = 5000;
        private long shutdownTimeoutMillis = 5000;

        public Builder enabled(String enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }

        public Builder rootRatio(double rootRatio) {
            this.rootRatio = rootRatio;
            return this;
        }

        public Builder contentMode(ContentCaptureMode contentMode) {
            this.contentMode = contentMode;
            return this;
        }

        public Builder limits(ContentLimits limits) {
            this.limits = limits;
            return this;
        }

        public Builder queueSize(int queueSize) {
            this.queueSize = queueSize;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder scheduleDelayMillis(long scheduleDelayMillis) {
            this.scheduleDelayMillis = scheduleDelayMillis;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder shutdownTimeoutMillis(long shutdownTimeoutMillis) {
            this.shutdownTimeoutMillis = shutdownTimeoutMillis;
            return this;
        }

        public AgentObservabilityConfig build() {
            return new AgentObservabilityConfig(
                    enabled, baseUrl, publicKey, secretKey, environment, serviceName, serviceVersion,
                    rootRatio, contentMode, limits, queueSize, batchSize,
                    scheduleDelayMillis, timeoutMillis, shutdownTimeoutMillis);
        }
    }
}

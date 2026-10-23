package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "typesafe")
public class TypeSafeDecisionProperties {

    private boolean enabled;
    private String apiKey;
    private String baseUrl = "https://api.typesafe.ai";
    private String modelName = "jev-1.13.0";
    private Duration timeout = Duration.ofSeconds(2);
    private int maxRetries;
    private double expertActivationThreshold = 0.6;

    public void validateExpertRouting() {
        if (!Double.isFinite(expertActivationThreshold) || expertActivationThreshold <= 0 || expertActivationThreshold >= 1) {
            throw new IllegalArgumentException("typesafe.expert-activation-threshold must be strictly between 0 and 1");
        }
    }

    public double getExpertActivationThreshold() {
        return expertActivationThreshold;
    }

    public void setExpertActivationThreshold(double expertActivationThreshold) {
        this.expertActivationThreshold = expertActivationThreshold;
    }

    public void validateEnabled() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("TYPESAFE_API_KEY is required when typesafe.enabled=true");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("typesafe.model-name must not be blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("typesafe.base-url must not be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("typesafe.timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("typesafe.max-retries must not be negative");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}

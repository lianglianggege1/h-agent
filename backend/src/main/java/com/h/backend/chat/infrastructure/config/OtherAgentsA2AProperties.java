package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agents.a2a.other-agents")
public class OtherAgentsA2AProperties {

    private String baseUrl = "http://localhost:8082";
    private boolean enabled = true;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String agentUrl(String agentId) {
        return normalizedBaseUrl() + "/a2a/agents/" + agentId;
    }

    private String normalizedBaseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8082";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}

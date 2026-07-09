package com.h.backend.chat.infrastructure.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agents.mcp.other-agents")
public class OtherAgentsMcpProperties {

    private boolean enabled = false;

    private String url = "http://localhost:8082/mcp";

    private int toolExecutionTimeoutSeconds = 4;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getToolExecutionTimeoutSeconds() {
        return toolExecutionTimeoutSeconds;
    }

    public void setToolExecutionTimeoutSeconds(int toolExecutionTimeoutSeconds) {
        this.toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds;
    }
}

package com.h.backend.chat.infrastructure.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agents.mcp.other-agents")
public class OtherAgentsMcpProperties {

    private boolean enabled = false;

    private String url = "http://localhost:8082/test1/mcp";

    /** Bearer Token，与 other-agents 侧对应 MCP endpoint 的 token 一致 */
    private String token;

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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getToolExecutionTimeoutSeconds() {
        return toolExecutionTimeoutSeconds;
    }

    public void setToolExecutionTimeoutSeconds(int toolExecutionTimeoutSeconds) {
        this.toolExecutionTimeoutSeconds = toolExecutionTimeoutSeconds;
    }
}

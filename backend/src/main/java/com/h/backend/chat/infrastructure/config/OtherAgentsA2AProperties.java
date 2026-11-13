package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agents.a2a.other-agents")
public class OtherAgentsA2AProperties {
    private boolean enabled = true;
    private String version = "0.1.0";

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getVersion() { return version; }

    public void setVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("A2A version must not be blank");
        }
        this.version = version;
    }
}

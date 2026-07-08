package com.h.backend.chat.infrastructure.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.shell")
public class ShellToolProperties {

    private boolean enabled = true;
    private int defaultTimeoutSeconds = 30;
    private int maxTimeoutSeconds = 120;
    private int maxOutputBytes = 100_000;
    private boolean inheritEnvironment = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public int getMaxTimeoutSeconds() {
        return maxTimeoutSeconds;
    }

    public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public boolean isInheritEnvironment() {
        return inheritEnvironment;
    }

    public void setInheritEnvironment(boolean inheritEnvironment) {
        this.inheritEnvironment = inheritEnvironment;
    }
}

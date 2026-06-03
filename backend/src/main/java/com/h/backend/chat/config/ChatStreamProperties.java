package com.h.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "chat.stream")
public class ChatStreamProperties {

    private Duration heartbeatInterval = Duration.ofSeconds(15);
    private int maxConcurrentPerUser = 2;
    private int maxConcurrentGlobal = 100;

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getMaxConcurrentPerUser() {
        return maxConcurrentPerUser;
    }

    public void setMaxConcurrentPerUser(int maxConcurrentPerUser) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
    }

    public int getMaxConcurrentGlobal() {
        return maxConcurrentGlobal;
    }

    public void setMaxConcurrentGlobal(int maxConcurrentGlobal) {
        this.maxConcurrentGlobal = maxConcurrentGlobal;
    }
}

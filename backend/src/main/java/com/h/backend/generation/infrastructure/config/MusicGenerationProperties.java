package com.h.backend.generation.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "generation.music")
public class MusicGenerationProperties {
    private String baseUrl = "https://api.minimax.cn";
    private Duration requestTimeout = Duration.ofMinutes(8);
    private int maxAudioBytes = 32 * 1024 * 1024;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }
    public int getMaxAudioBytes() { return maxAudioBytes; }
    public void setMaxAudioBytes(int value) { maxAudioBytes = value; }
}

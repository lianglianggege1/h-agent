package com.h.backend.voice.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {
    private boolean enabled;
    private String livekitUrl = "wss://livekit.home.arpa";
    private String livekitApiUrl = "http://127.0.0.1:7880";
    private String livekitApiKey = "";
    private String livekitApiSecret = "";
    private String workerToken = "";
    private String agentName = "h-agent-voice";
    private long prepareTimeoutMs = 30_000;
    private long workerLeaseMs = 15_000;
    private long reconnectGraceMs = 10_000;
    private long maxCallMs = 1_800_000;
    private long generationTimeoutMs = 120_000;
    private int maxReplyChars = 8_000;

    public void requireConfigured() {
        if (!enabled || livekitApiKey.isBlank() || livekitApiSecret.length() < 32 || workerToken.length() < 32) {
            throw new com.h.backend.common.exception.BusinessException(50300, "语音服务尚未配置");
        }
    }
}

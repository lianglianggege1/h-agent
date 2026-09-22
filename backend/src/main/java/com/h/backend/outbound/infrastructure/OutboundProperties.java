package com.h.backend.outbound.infrastructure;

import com.h.backend.common.exception.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "outbound")
public class OutboundProperties {
    private boolean enabled = false;
    private List<String> allowedExtensions = List.of("1000", "1001");
    private String internalToken = "";
    private boolean mediaVerified = false;
    private long prepareTimeoutMs = 30_000;
    private long ringTimeoutMs = 25_000;
    private long replyTimeoutMs = 120_000;
    private long maxCallDurationMs = 1_800_000;

    public void requireEnabled() {
        if (!enabled) {
            throw new BusinessException(50300, "外呼服务未启用");
        }
        if (internalToken == null || internalToken.length() < 32) {
            throw new BusinessException(50300, "外呼内部 Token 尚未配置或长度不足 32 字符");
        }
        if (!mediaVerified) {
            throw new BusinessException(50300, "双向媒体与播放回执尚未完成验收");
        }
    }

    public boolean extensionAllowed(String ext) {
        return allowedExtensions != null && allowedExtensions.contains(ext);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getAllowedExtensions() { return allowedExtensions; }
    public void setAllowedExtensions(List<String> allowedExtensions) { this.allowedExtensions = allowedExtensions; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    public boolean isMediaVerified() { return mediaVerified; }
    public void setMediaVerified(boolean mediaVerified) { this.mediaVerified = mediaVerified; }
    public long getPrepareTimeoutMs() { return prepareTimeoutMs; }
    public void setPrepareTimeoutMs(long prepareTimeoutMs) { this.prepareTimeoutMs = prepareTimeoutMs; }
    public long getRingTimeoutMs() { return ringTimeoutMs; }
    public void setRingTimeoutMs(long ringTimeoutMs) { this.ringTimeoutMs = ringTimeoutMs; }
    public long getReplyTimeoutMs() { return replyTimeoutMs; }
    public void setReplyTimeoutMs(long replyTimeoutMs) { this.replyTimeoutMs = replyTimeoutMs; }
    public long getMaxCallDurationMs() { return maxCallDurationMs; }
    public void setMaxCallDurationMs(long maxCallDurationMs) { this.maxCallDurationMs = maxCallDurationMs; }
}

package com.h.backend.chat.infrastructure.filesystem;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.filesystem")
public class AssistantFileProperties {

    private String baseDir = "/tmp/h-agent/assistant-files";
    private long maxFileSizeBytes = 10 * 1024 * 1024;

    public String getBaseDir() {
        return baseDir;
    }

    public void setBaseDir(String baseDir) {
        this.baseDir = baseDir;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }
}

package com.h.backend.chat.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "langfuse")
public record LangfuseTelemetryProperties(
        boolean enabled,
        String otlpEndpoint,
        String publicKey,
        String secretKey
) {
}

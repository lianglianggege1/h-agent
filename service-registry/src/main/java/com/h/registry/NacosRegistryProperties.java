package com.h.registry;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

@Validated
@ConfigurationProperties("h-agent.registry")
public record NacosRegistryProperties(
        @NotBlank @DefaultValue("localhost:8848") String serverAddr,
        @NotBlank @DefaultValue("public") String namespace,
        @NotBlank @DefaultValue("DEFAULT_GROUP") String group,
        @NotBlank @DefaultValue("DEFAULT") String cluster,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String ip,
        @Min(0) @Max(65535) @DefaultValue("0") int port,
        @Min(1) @DefaultValue("5000") long timeoutMs,
        Map<String, String> metadata
) {
    // Do not let generated record diagnostics expose credentials.
    @Override
    public String toString() {
        return "NacosRegistryProperties[serverAddr=" + serverAddr + ", namespace=" + namespace + "]";
    }
}

package com.h.backend.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilitySdk;
import com.h.agent.observability.EnvFileLoader;
import com.h.agent.observability.LangfuseRuntimeStatus;
import com.h.agent.observability.semantic.ContentCaptureMode;
import com.h.agent.observability.semantic.ContentLimits;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Map;

/**
 * 统一 Agent 观测装配：以 Spring 属性为最高优先级，其次 OS 环境变量，最后仓库根 .env。
 * Langfuse 缺失或配置错误时退化为 no-op，不阻塞应用启动。
 */
@Slf4j
@Configuration
public class AgentObservabilitySpringConfig {

    @Bean(destroyMethod = "close")
    public AgentObservability agentObservability(Environment environment) {
        Map<String, String> envFile = EnvFileLoader.load();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .enabled(resolve(environment, envFile, "agent-observability.enabled", "AGENT_OBSERVABILITY_ENABLED", "auto"))
                .baseUrl(resolve(environment, envFile, "agent-observability.langfuse.base-url", "LANGFUSE_BASE_URL", null))
                .publicKey(resolve(environment, envFile, "agent-observability.langfuse.public-key", "LANGFUSE_PUBLIC_KEY", null))
                .secretKey(resolve(environment, envFile, "agent-observability.langfuse.secret-key", "LANGFUSE_SECRET_KEY", null))
                .environment(resolve(environment, envFile, "agent-observability.langfuse.environment", "LANGFUSE_ENVIRONMENT", "local"))
                .serviceName(environment.getProperty("agent-observability.service-name",
                        environment.getProperty("spring.application.name", "backend")))
                .serviceVersion(environment.getProperty("agent-observability.service-version", "0.0.1"))
                .rootRatio(resolveDouble(environment, envFile,
                        "agent-observability.sampling.root-ratio", "LANGFUSE_SAMPLE_RATE", 1.0d))
                .contentMode(resolveContentMode(environment, envFile))
                .limits(ContentLimits.defaults())
                .queueSize(resolveInt(environment, "agent-observability.export.queue-size", 2048))
                .batchSize(resolveInt(environment, "agent-observability.export.batch-size", 512))
                .scheduleDelayMillis(resolveLong(environment, "agent-observability.export.schedule-delay-millis", 1000L))
                .timeoutMillis(resolveLong(environment, "agent-observability.export.timeout-millis", 5000L))
                .shutdownTimeoutMillis(resolveLong(environment, "agent-observability.export.shutdown-timeout-millis", 5000L))
                .build();
        AgentObservability observability = AgentObservabilitySdk.build(config);
        LangfuseRuntimeStatus status = observability.status();
        if (status == LangfuseRuntimeStatus.ACTIVE) {
            log.info("Agent observability ACTIVE: service={} environment={} rootRatio={} contentMode={}",
                    config.serviceName(), config.environment(), config.rootRatio(), config.contentMode());
        } else {
            log.info("Agent observability {} (no-op mode); business behavior unchanged", status);
        }
        return observability;
    }

    private static String resolve(Environment environment,
                                  Map<String, String> envFile,
                                  String property,
                                  String envKey,
                                  String fallback) {
        String fromProperty = environment.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = EnvFileLoader.resolve(envFile, envKey);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return fallback;
    }

    private static double resolveDouble(Environment environment,
                                        Map<String, String> envFile,
                                        String property,
                                        String envKey,
                                        double fallback) {
        String raw = resolve(environment, envFile, property, envKey, null);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int resolveInt(Environment environment, String property, int fallback) {
        String raw = environment.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long resolveLong(Environment environment, String property, long fallback) {
        String raw = environment.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static ContentCaptureMode resolveContentMode(Environment environment, Map<String, String> envFile) {
        String raw = resolve(environment, envFile, "agent-observability.content.mode", "LANGFUSE_CONTENT_MODE", "structured");
        try {
            return ContentCaptureMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ContentCaptureMode.STRUCTURED;
        }
    }
}

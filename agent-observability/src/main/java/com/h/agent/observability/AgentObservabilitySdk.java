package com.h.agent.observability;

import java.net.URI;
import java.util.logging.Logger;

public final class AgentObservabilitySdk {

    private static final Logger LOG = Logger.getLogger(AgentObservabilitySdk.class.getName());

    private AgentObservabilitySdk() {
    }

    public static AgentObservability build(AgentObservabilityConfig config) {
        LangfuseRuntimeStatus status = resolveStatus(config);
        if (status != LangfuseRuntimeStatus.ACTIVE) {
            LOG.info("H Agent observability is " + status + "; using no-op implementation");
            return NoopAgentObservability.withStatus(status);
        }
        LOG.info("H Agent observability is ACTIVE: langfuse=" + config.baseUrl()
                + " service=" + config.serviceName()
                + " environment=" + config.environment()
                + " rootRatio=" + config.rootRatio()
                + " contentMode=" + config.contentMode());
        return DefaultAgentObservability.build(config);
    }

    public static LangfuseRuntimeStatus resolveStatus(AgentObservabilityConfig config) {
        if (config.explicitlyDisabled()) {
            return LangfuseRuntimeStatus.DISABLED_EXPLICITLY;
        }
        boolean hasUrl = notBlank(config.baseUrl());
        boolean hasPublicKey = notBlank(config.publicKey());
        boolean hasSecretKey = notBlank(config.secretKey());
        boolean configured = hasUrl && hasPublicKey && hasSecretKey;
        boolean anyPresent = hasUrl || hasPublicKey || hasSecretKey;
        if (!configured && !anyPresent) {
            return LangfuseRuntimeStatus.DISABLED_NOT_CONFIGURED;
        }
        if (!configured || invalidUrl(config.baseUrl())) {
            return LangfuseRuntimeStatus.DEGRADED_MISCONFIGURED;
        }
        return LangfuseRuntimeStatus.ACTIVE;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean invalidUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            return scheme == null || (!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null;
        } catch (IllegalArgumentException ex) {
            return true;
        }
    }
}

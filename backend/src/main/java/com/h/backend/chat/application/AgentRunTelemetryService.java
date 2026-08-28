package com.h.backend.chat.application;

public interface AgentRunTelemetryService {

    TelemetryRun startRun(String sessionId, Long userId, Long promptId);

    default TelemetryRun resumeRun(String sessionId, Long userId, Long promptId, String traceParent) {
        return startRun(sessionId, userId, promptId);
    }

    void markSuccess(TelemetryRun telemetryRun);

    void markFailure(TelemetryRun telemetryRun, Throwable error);

    default void markPaused(TelemetryRun telemetryRun) {
        markSuccess(telemetryRun);
    }

    record TelemetryRun(io.opentelemetry.api.trace.Span span, String traceId) {
    }
}

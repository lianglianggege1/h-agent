package com.h.backend.chat.service;

public interface AgentRunTelemetryService {

    TelemetryRun startRun(String sessionId, Long userId, Long promptId);

    void markSuccess(TelemetryRun telemetryRun);

    void markFailure(TelemetryRun telemetryRun, Throwable error);

    record TelemetryRun(io.opentelemetry.api.trace.Span span, String traceId) {
    }
}

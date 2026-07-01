package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.AgentRunTelemetryService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.stereotype.Service;

@Service
public class AgentRunTelemetryServiceImpl implements AgentRunTelemetryService {

    private final Tracer tracer;

    public AgentRunTelemetryServiceImpl(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.h.backend.chat.domain.agent-run");
    }

    @Override
    public TelemetryRun startRun(String sessionId, Long userId, Long promptId) {
        Span span = tracer.spanBuilder("chat.agent.run")
                .setAttribute("chat.session_id", sessionId)
                .setAttribute("chat.user_id", userId == null ? -1L : userId)
                .setAttribute("chat.prompt_id", promptId == null ? -1L : promptId)
                .startSpan();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        return new TelemetryRun(span, traceId);
    }

    @Override
    public void markSuccess(TelemetryRun telemetryRun) {
        if (telemetryRun == null || telemetryRun.span() == null) {
            return;
        }
        telemetryRun.span().setStatus(StatusCode.OK);
        telemetryRun.span().end();
    }

    @Override
    public void markFailure(TelemetryRun telemetryRun, Throwable error) {
        if (telemetryRun == null || telemetryRun.span() == null) {
            return;
        }
        if (error != null) {
            telemetryRun.span().recordException(error);
            telemetryRun.span().setStatus(StatusCode.ERROR, error.getMessage());
        } else {
            telemetryRun.span().setStatus(StatusCode.ERROR);
        }
        telemetryRun.span().end();
    }
}

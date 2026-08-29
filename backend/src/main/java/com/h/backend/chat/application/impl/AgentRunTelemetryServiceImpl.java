package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.AgentRunTelemetryService;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentRunTelemetryServiceImpl implements AgentRunTelemetryService {

    private static final TextMapGetter<Map<String, String>> TRACE_PARENT_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier.get(key);
                }
            };

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
        return new TelemetryRun(span, traceId, toTraceParent(span));
    }

    @Override
    public TelemetryRun resumeRun(String sessionId, Long userId, Long promptId, String traceParent) {
        SpanBuilder builder = tracer.spanBuilder("chat.agent.run.resume")
                .setAttribute("chat.session_id", sessionId)
                .setAttribute("chat.user_id", userId == null ? -1L : userId)
                .setAttribute("chat.prompt_id", promptId == null ? -1L : promptId)
                .setAttribute("chat.hitl.resumed", true)
                .setAttribute("chat.hitl.trace_parent", traceParent == null ? "" : traceParent);
        if (traceParent != null && !traceParent.isBlank()) {
            Context parent = W3CTraceContextPropagator.getInstance().extract(
                    Context.root(), Map.of("traceparent", traceParent), TRACE_PARENT_GETTER
            );
            builder.setParent(parent);
        }
        Span span = builder.startSpan();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        return new TelemetryRun(span, traceId, toTraceParent(span));
    }

    private String toTraceParent(Span span) {
        var context = span.getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-"
                + (context.isSampled() ? "01" : "00");
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

    @Override
    public void markPaused(TelemetryRun telemetryRun) {
        if (telemetryRun == null || telemetryRun.span() == null) {
            return;
        }
        telemetryRun.span().setAttribute("chat.hitl.paused", true);
        telemetryRun.span().setStatus(StatusCode.UNSET);
        telemetryRun.span().end();
    }
}

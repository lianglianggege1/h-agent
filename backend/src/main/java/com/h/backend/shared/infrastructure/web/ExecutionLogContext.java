package com.h.backend.shared.infrastructure.web;

import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import com.h.agent.observability.lifecycle.ObservationScope;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Captures request correlation before dispatch, including when tracing export is disabled. */
public final class ExecutionLogContext {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String SPAN_ID_KEY = "spanId";

    private ExecutionLogContext() {
    }

    /** Only persist correlation fields, never arbitrary MDC values or credentials. */
    public static Map<String, String> captureCorrelation() {
        Map<String, String> values = new HashMap<>();
        var span = Span.current().getSpanContext();
        String trace = span.isValid() ? span.getTraceId() : MDC.get(TRACE_ID_KEY);
        String spanId = span.isValid() ? span.getSpanId() : MDC.get(SPAN_ID_KEY);
        values.put(TRACE_ID_KEY, trace == null ? randomId() : trace);
        values.put(SPAN_ID_KEY, spanId == null ? randomId().substring(0, 16) : spanId);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), values, Map::put);
        return values;
    }

    public static ObservationScope restoreCorrelation(Map<String, String> values) {
        // A legacy queued task gets its own correlation, never the scheduler's current trace.
        Map<String, String> correlation = values == null ? new HashMap<>() : new HashMap<>(values);
        correlation.computeIfAbsent(TRACE_ID_KEY, ignored -> randomId());
        correlation.computeIfAbsent(SPAN_ID_KEY, ignored -> randomId().substring(0, 16));
        Context context = W3CTraceContextPropagator.getInstance().extract(Context.root(), correlation,
                new TextMapGetter<Map<String, String>>() {
                    @Override
                    public Iterable<String> keys(Map<String, String> carrier) {
                        return carrier.keySet();
                    }

                    @Override
                    public String get(Map<String, String> carrier, String key) {
                        return carrier.get(key);
                    }
                });
        return new ExecutionContextSnapshot(context, correlation).scope();
    }

    public static Runnable wrap(Runnable task) {
        Context context = Context.current();
        Map<String, String> captured = MDC.getCopyOfContextMap();
        Map<String, String> values = captured == null ? new HashMap<>() : new HashMap<>(captured);
        values.computeIfAbsent(TRACE_ID_KEY, ignored -> randomId());
        values.computeIfAbsent(SPAN_ID_KEY, ignored -> randomId().substring(0, 16));
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try (var scope = context.makeCurrent()) {
                MDC.setContextMap(values);
                task.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

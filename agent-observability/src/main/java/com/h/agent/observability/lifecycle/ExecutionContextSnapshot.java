package com.h.agent.observability.lifecycle;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/** Captures both tracing and logging context, including when observations are disabled. */
public final class ExecutionContextSnapshot {
    private final Context context;
    private final Map<String, String> mdc;

    public ExecutionContextSnapshot(Context context, Map<String, String> mdc) {
        this.context = context;
        this.mdc = mdc == null ? Map.of() : Map.copyOf(mdc);
    }

    public static ExecutionContextSnapshot capture() {
        return new ExecutionContextSnapshot(Context.current(), MDC.getCopyOfContextMap());
    }

    public static ExecutionContextSnapshot forObservation(ObservationContext observation) {
        if (observation == null) return capture();
        var context = observation.otelContext();
        var values = MDC.getCopyOfContextMap();
        var mdc = values == null ? new HashMap<String, String>() : values;
        var span = Span.fromContext(context).getSpanContext();
        if (span.isValid()) {
            mdc.put("traceId", span.getTraceId());
            mdc.put("spanId", span.getSpanId());
        }
        return new ExecutionContextSnapshot(context, mdc);
    }

    public ObservationScope scope() {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        var scope = context.makeCurrent();
        MDC.setContextMap(mdc);
        return () -> {
            try {
                scope.close();
            } finally {
                if (previous == null) MDC.clear();
                else MDC.setContextMap(previous);
            }
        };
    }
}

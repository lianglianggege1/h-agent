package com.h.backend.shared.infrastructure.web;

import io.opentelemetry.context.Context;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Captures request correlation before dispatch, including when tracing export is disabled. */
public final class ExecutionLogContext {
    private ExecutionLogContext() {
    }

    public static Runnable wrap(Runnable task) {
        Context context = Context.current();
        Map<String, String> captured = MDC.getCopyOfContextMap();
        Map<String, String> values = captured == null ? new HashMap<>() : new HashMap<>(captured);
        values.computeIfAbsent(TraceMdcFilter.TRACE_ID_KEY, ignored -> randomId());
        values.computeIfAbsent(TraceMdcFilter.SPAN_ID_KEY, ignored -> randomId().substring(0, 16));
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

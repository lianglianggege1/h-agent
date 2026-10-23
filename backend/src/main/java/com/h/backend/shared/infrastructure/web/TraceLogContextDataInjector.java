package com.h.backend.shared.infrastructure.web;

import io.opentelemetry.api.trace.Span;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.ThreadContextDataInjector;
import org.apache.logging.log4j.util.ReadOnlyStringMap;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;

import java.util.List;

/** Captures the active OTel IDs on the logging thread, before AsyncAppender handoff. */
public final class TraceLogContextDataInjector extends ThreadContextDataInjector.ForDefaultThreadContextMap {

    @Override
    public StringMap injectContextData(List<Property> properties, StringMap reusable) {
        return withSpan(super.injectContextData(properties, reusable));
    }

    @Override
    public ReadOnlyStringMap rawContextData() {
        return withSpan(super.rawContextData());
    }

    private StringMap withSpan(ReadOnlyStringMap contextData) {
        // Never mutate Log4j's frozen/shared MDC map. Unsampled spans also have valid IDs.
        StringMap result = new SortedArrayStringMap(contextData);
        var span = Span.current().getSpanContext();
        if (span.isValid()) {
            result.putValue(TraceMdcFilter.TRACE_ID_KEY, span.getTraceId());
            result.putValue(TraceMdcFilter.SPAN_ID_KEY, span.getSpanId());
        }
        result.freeze();
        return result;
    }
}

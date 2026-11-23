package com.h.agent.observability.lifecycle;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.IdGenerator;

/**
 * Opaque, non-serializable carrier of the active observation context.
 * Business code passes it along execution commands but never reads trace internals from it.
 */
public final class ObservationContext {

    private final io.opentelemetry.context.Context otelContext;
    private final ObservationMetadata metadata;

    private ObservationContext(io.opentelemetry.context.Context otelContext, ObservationMetadata metadata) {
        this.otelContext = otelContext;
        this.metadata = metadata == null ? ObservationMetadata.empty() : metadata;
    }

    public static ObservationContext of(io.opentelemetry.context.Context otelContext, ObservationMetadata metadata) {
        return new ObservationContext(otelContext, metadata);
    }

    public static ObservationContext root() {
        return new ObservationContext(io.opentelemetry.context.Context.root(), null);
    }

    /**
     * Reconnects durable work that stored only the trace ID. The original parent and sampling decision
     * are unknown, so the recovered context is non-recording and is used for log and header correlation.
     */
    public static ObservationContext recoverTraceId(String traceId) {
        if (traceId == null || !TraceId.isValid(traceId)) return root();
        var context = SpanContext.create(traceId, IdGenerator.random().generateSpanId(),
                TraceFlags.getDefault(), TraceState.getDefault());
        return of(Span.wrap(context).storeInContext(io.opentelemetry.context.Context.root()), null);
    }

    public io.opentelemetry.context.Context otelContext() {
        return otelContext;
    }

    public ObservationMetadata metadata() {
        return metadata;
    }
}

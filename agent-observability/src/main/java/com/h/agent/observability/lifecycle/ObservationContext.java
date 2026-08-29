package com.h.agent.observability.lifecycle;

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

    public io.opentelemetry.context.Context otelContext() {
        return otelContext;
    }

    public ObservationMetadata metadata() {
        return metadata;
    }
}

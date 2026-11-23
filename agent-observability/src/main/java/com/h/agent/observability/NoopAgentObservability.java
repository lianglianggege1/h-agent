package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.IdGenerator;

import java.util.Map;

public final class NoopAgentObservability implements AgentObservability {

    private static final NoopAgentObservability INSTANCE =
            new NoopAgentObservability(LangfuseRuntimeStatus.DISABLED_NOT_CONFIGURED);
    private static final IdGenerator IDS = IdGenerator.random();
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final LangfuseRuntimeStatus status;

    private NoopAgentObservability(LangfuseRuntimeStatus status) {
        this.status = status;
    }

    public static NoopAgentObservability getInstance() {
        return INSTANCE;
    }

    static NoopAgentObservability withStatus(LangfuseRuntimeStatus status) {
        return new NoopAgentObservability(status);
    }

    static AgentExecutionObservation noopExecution() {
        return new NoopExecution(ObservationContext.root());
    }

    static AgentObservation noopObservation() {
        return new NoopObservation(ObservationContext.root());
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public LangfuseRuntimeStatus status() {
        return status;
    }

    @Override
    public AgentExecutionObservation start(AgentExecutionStart start) {
        return new NoopExecution(child(currentContext(), false));
    }

    @Override
    public AgentExecutionObservation startMaintenance(AgentExecutionStart start, ObservationContext linkedPrimary) {
        return noopExecution();
    }

    @Override
    public AgentObservation span(ObservationSpec spec, ObservationContext parent) {
        return new NoopObservation(child(parent == null ? currentContext() : parent, false));
    }

    @Override
    public AgentObservation serverSpan(ObservationSpec spec, ObservationContext parent) {
        return new NoopObservation(child(parent == null ? currentContext() : parent, true));
    }

    @Override
    public ObservationContext currentContext() {
        return ObservationContext.of(Context.current(), null);
    }

    @Override
    public ObservationScope scope(ObservationContext context) {
        if (context == null) return () -> { };
        var scope = context.otelContext().makeCurrent();
        return scope::close;
    }

    @Override
    public ObservationContext extract(Map<String, String> headers) {
        return ObservationContext.of(PROPAGATOR.extract(Context.root(), headers, HEADERS), null);
    }

    @Override
    public void inject(ObservationContext context, Map<String, String> headers) {
        if (context != null) PROPAGATOR.inject(context.otelContext(), headers, Map::put);
    }

    private static ObservationContext child(ObservationContext parent, boolean newRoot) {
        var existing = Span.fromContext(parent.otelContext()).getSpanContext();
        if (!existing.isValid() && !newRoot) return parent;
        var ids = SpanContext.create(existing.isValid() ? existing.getTraceId() : IDS.generateTraceId(),
                IDS.generateSpanId(), existing.isValid() ? existing.getTraceFlags() : TraceFlags.getDefault(),
                existing.isValid() ? existing.getTraceState() : TraceState.getDefault());
        return ObservationContext.of(Span.wrap(ids).storeInContext(parent.otelContext()), parent.metadata());
    }

    @Override
    public void close() {
    }

    private record NoopExecution(ObservationContext observationContext) implements AgentExecutionObservation {

        @Override
        public String traceId() {
            return traceIdOf(observationContext);
        }

        @Override
        public ObservationScope scope() {
            return INSTANCE.scope(observationContext);
        }

        @Override
        public void succeed(SemanticContent output) {
        }

        @Override
        public void fail(Throwable error) {
        }

        @Override
        public void cancel(String reason) {
        }

        @Override
        public void close() {
        }
    }

    private static String traceIdOf(ObservationContext context) {
        var span = Span.fromContext(context.otelContext()).getSpanContext();
        return span.isValid() ? span.getTraceId() : null;
    }

    private record NoopObservation(ObservationContext context) implements AgentObservation {

        @Override
        public String traceId() {
            return traceIdOf(context);
        }

        @Override
        public String spanId() {
            var span = Span.fromContext(context.otelContext()).getSpanContext();
            return span.isValid() ? span.getSpanId() : null;
        }

        @Override
        public void attribute(String key, String value) {
        }

        @Override
        public void attribute(String key, long value) {
        }

        @Override
        public void usage(Number inputTokens, Number outputTokens, Number totalTokens) {
        }

        @Override
        public void input(SemanticContent content) {
        }

        @Override
        public void output(SemanticContent content) {
        }

        @Override
        public void succeed() {
        }

        @Override
        public void fail(Throwable error) {
        }

        @Override
        public void cancel(String reason) {
        }

        @Override
        public void close() {
        }
    }
}

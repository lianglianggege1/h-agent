package com.h.agent.observability;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.context.Context;

/**
 * Forwards only spans produced by the H Agent instrumentation scope to the wrapped processor,
 * so Spring/HTTP/SQL auto-instrumentation never reaches Langfuse.
 */
public final class ScopeFilteringSpanProcessor implements SpanProcessor {

    private final SpanProcessor delegate;

    public ScopeFilteringSpanProcessor(SpanProcessor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (inScope(span)) {
            delegate.onStart(parentContext, span);
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (inScope(span)) {
            delegate.onEnd(span);
        }
    }

    private boolean inScope(ReadableSpan span) {
        return AgentObservability.INSTRUMENTATION_SCOPE
                .equals(span.getInstrumentationScopeInfo().getName());
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }

    @Override
    public void close() {
        delegate.close();
    }
}

package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;

import java.util.Map;

public final class NoopAgentObservability implements AgentObservability {

    private static final NoopAgentObservability INSTANCE =
            new NoopAgentObservability(LangfuseRuntimeStatus.DISABLED_NOT_CONFIGURED);
    private static final ObservationScope NOOP_SCOPE = () -> {
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
        return new NoopExecution();
    }

    static AgentObservation noopObservation() {
        return new NoopObservation();
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
        return noopExecution();
    }

    @Override
    public AgentExecutionObservation startMaintenance(AgentExecutionStart start, ObservationContext linkedPrimary) {
        return noopExecution();
    }

    @Override
    public AgentObservation span(ObservationSpec spec, ObservationContext parent) {
        return noopObservation();
    }

    @Override
    public ObservationContext currentContext() {
        return ObservationContext.root();
    }

    @Override
    public ObservationScope scope(ObservationContext context) {
        return NOOP_SCOPE;
    }

    @Override
    public ObservationContext extract(Map<String, String> headers) {
        return ObservationContext.root();
    }

    @Override
    public void inject(ObservationContext context, Map<String, String> headers) {
    }

    @Override
    public void close() {
    }

    private static final class NoopExecution implements AgentExecutionObservation {

        @Override
        public String traceId() {
            return null;
        }

        @Override
        public ObservationContext observationContext() {
            return ObservationContext.root();
        }

        @Override
        public ObservationScope scope() {
            return NOOP_SCOPE;
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

    private static final class NoopObservation implements AgentObservation {

        @Override
        public String traceId() {
            return null;
        }

        @Override
        public String spanId() {
            return null;
        }

        @Override
        public ObservationContext context() {
            return ObservationContext.root();
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

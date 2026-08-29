package com.h.agent.observability.lifecycle;

import com.h.agent.observability.semantic.SemanticContent;

public interface AgentExecutionObservation extends AutoCloseable {

    String traceId();

    ObservationContext observationContext();

    ObservationScope scope();

    void succeed(SemanticContent output);

    void fail(Throwable error);

    void cancel(String reason);

    @Override
    void close();
}

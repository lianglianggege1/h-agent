package com.h.agent.observability.lifecycle;

/**
 * Thread-local scope over an {@link ObservationContext}. Closing it on the same thread
 * restores the previous context. Implementations never throw from {@link #close()}.
 */
public interface ObservationScope extends AutoCloseable {

    @Override
    void close();
}

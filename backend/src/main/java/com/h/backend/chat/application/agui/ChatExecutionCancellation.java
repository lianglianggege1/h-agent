package com.h.backend.chat.application.agui;

import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Explicit user cancellation, independent of the lifetime of an HTTP subscriber. */
public final class ChatExecutionCancellation {
    public static final String CONTEXT_KEY = ChatExecutionCancellation.class.getName();
    private final List<Runnable> callbacks = new ArrayList<>();
    private boolean cancelled;

    public static ChatExecutionCancellation from(FluxSink<?> sink) {
        return sink.contextView().getOrDefault(CONTEXT_KEY, null);
    }

    public synchronized boolean isCancelled() { return cancelled; }

    public void onCancel(Runnable callback) {
        synchronized (this) {
            if (!cancelled) {
                callbacks.add(callback);
                return;
            }
        }
        callback.run();
    }

    public void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        RuntimeException failure = null;
        for (Runnable callback : pending) {
            try { callback.run(); }
            catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }

    public static void check(FluxSink<?> sink) {
        ChatExecutionCancellation token = from(sink);
        if (token != null && token.isCancelled()) throw new CancellationException("用户已停止任务");
    }
}

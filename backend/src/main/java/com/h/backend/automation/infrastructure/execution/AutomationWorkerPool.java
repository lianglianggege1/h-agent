package com.h.backend.automation.infrastructure.execution;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class AutomationWorkerPool implements AutoCloseable {
    private final ExecutorService executor;

    AutomationWorkerPool(ExecutorService executor) {
        this.executor = executor;
    }

    public Future<?> submit(Runnable work) {
        return executor.submit(work);
    }

    public void execute(Runnable work) {
        executor.execute(work);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}

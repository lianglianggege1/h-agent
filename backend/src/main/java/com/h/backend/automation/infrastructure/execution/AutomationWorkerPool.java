package com.h.backend.automation.infrastructure.execution;

import java.util.concurrent.ExecutorService;

public final class AutomationWorkerPool implements AutoCloseable {
    private final ExecutorService executor;

    AutomationWorkerPool(ExecutorService executor) {
        this.executor = executor;
    }

    public void submit(Runnable work) {
        executor.submit(work);
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}

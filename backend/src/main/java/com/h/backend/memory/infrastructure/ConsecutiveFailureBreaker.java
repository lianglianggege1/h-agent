package com.h.backend.memory.infrastructure;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 连续故障熔断：连续失败达到阈值后短路一段时间，成功即复位。 */
public class ConsecutiveFailureBreaker {

    private final int failureThreshold;
    private final long openWindowMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public ConsecutiveFailureBreaker(int failureThreshold, long openWindowMillis) {
        this.failureThreshold = failureThreshold;
        this.openWindowMillis = openWindowMillis;
    }

    public boolean allowRequest() {
        Instant opened = openedAt.get();
        if (opened == null) {
            return true;
        }
        if (Instant.now().toEpochMilli() - opened.toEpochMilli() >= openWindowMillis) {
            openedAt.compareAndSet(opened, null);
            consecutiveFailures.set(0);
            return true;
        }
        return false;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(null);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.compareAndSet(null, Instant.now());
        }
    }

    public boolean isOpen() {
        return !allowRequest();
    }
}

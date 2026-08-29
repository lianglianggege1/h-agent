package com.h.backend.memory.infrastructure;

import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** 指数退避 + jitter：delay = min(initial * multiplier^(attempt-1), max) * (1 ± jitter)。 */
public class CaptureBackoffPolicy {

    private final LongTermMemoryProperties.Capture capture;

    public CaptureBackoffPolicy(LongTermMemoryProperties.Capture capture) {
        this.capture = capture;
    }

    public Duration delayForAttempt(int attempt) {
        double base = capture.getInitialDelay().toMillis();
        double multiplier = Math.max(1.0, capture.getMultiplier());
        double delay = base;
        for (int i = 1; i < attempt; i++) {
            delay *= multiplier;
        }
        double capped = Math.min(delay, capture.getMaxDelay().toMillis());
        double jitterRatio = Math.max(0.0, capture.getJitter());
        double jitter = capped * jitterRatio;
        double randomized = ThreadLocalRandom.current().nextDouble(capped - jitter, capped + jitter + 1);
        return Duration.ofMillis(Math.max(0, (long) randomized));
    }
}

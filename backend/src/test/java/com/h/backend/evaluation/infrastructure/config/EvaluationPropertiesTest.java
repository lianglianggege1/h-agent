package com.h.backend.evaluation.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationPropertiesTest {

    @Test
    void defaultsToDisabledAndSerialExecution() {
        EvaluationProperties properties = new EvaluationProperties();

        assertEquals(false, properties.isEnabled());
        assertEquals(1, properties.getMaxConcurrentTrials());
        assertEquals(Duration.ofSeconds(120), properties.getDefaultTrialTimeout());
    }

    @Test
    void rejectsUnsafeLimits() {
        EvaluationProperties properties = new EvaluationProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setMaxConcurrentTrials(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setMaxConcurrentTrials(2));
        assertThrows(IllegalArgumentException.class, () -> properties.setDefaultTrialTimeout(Duration.ZERO));
    }
}

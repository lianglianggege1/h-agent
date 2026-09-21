package com.h.backend.evaluation.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationIsolationGuardTest {

    @Test
    void disabledEvaluationDoesNotRequireAnIsolatedEnvironment() {
        EvaluationProperties properties = new EvaluationProperties();

        assertDoesNotThrow(() -> EvaluationIsolationGuard.validate(
                properties, "jdbc:postgresql://db/h_agent_db", 0
        ));
    }

    @Test
    void enabledEvaluationRejectsTheDailyDatabase() {
        EvaluationProperties properties = enabledProperties();

        assertThrows(IllegalStateException.class, () -> EvaluationIsolationGuard.validate(
                properties, "jdbc:postgresql://db/h_agent_db", 15
        ));
    }

    @Test
    void enabledEvaluationRejectsTheWrongRedisDatabase() {
        EvaluationProperties properties = enabledProperties();

        assertThrows(IllegalStateException.class, () -> EvaluationIsolationGuard.validate(
                properties, "jdbc:postgresql://db/h_agent_eval", 0
        ));
    }

    @Test
    void enabledEvaluationAcceptsTheDedicatedEnvironment() {
        EvaluationProperties properties = enabledProperties();

        assertDoesNotThrow(() -> EvaluationIsolationGuard.validate(
                properties, "jdbc:postgresql://db/h_agent_eval", 15
        ));
    }

    private static EvaluationProperties enabledProperties() {
        EvaluationProperties properties = new EvaluationProperties();
        properties.setEnabled(true);
        properties.setRequiredJdbcUrlMarker("h_agent_eval");
        properties.setRequiredRedisDatabase(15);
        return properties;
    }
}

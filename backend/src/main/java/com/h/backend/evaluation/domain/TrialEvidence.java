package com.h.backend.evaluation.domain;

import java.util.Map;

public record TrialEvidence(
        EvaluationOutcome outcome,
        Map<String, Object> observed
) {
    public TrialEvidence {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
        observed = observed == null ? Map.of() : Map.copyOf(observed);
    }
}

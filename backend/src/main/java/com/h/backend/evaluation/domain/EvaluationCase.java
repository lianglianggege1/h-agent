package com.h.backend.evaluation.domain;

import java.util.Map;
import java.util.List;

public record EvaluationCase(
        String caseId,
        String scenarioId,
        String input,
        Map<String, Object> expected,
        List<GraderRequirement> graders
) {
    public EvaluationCase(String caseId, String scenarioId, String input, Map<String, Object> expected) {
        this(caseId, scenarioId, input, expected, List.of());
    }

    public EvaluationCase {
        if (caseId == null || caseId.isBlank() || scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("caseId and scenarioId are required");
        }
        expected = expected == null ? Map.of() : Map.copyOf(expected);
        graders = graders == null ? List.of() : List.copyOf(graders);
    }
}

package com.h.backend.evaluation.domain;

import java.util.List;

public record TrialResult(
        Trial trial,
        List<Score> scores,
        GateVerdict gateVerdict,
        String errorMessage
) {
    public TrialResult(Trial trial, List<Score> scores, GateVerdict gateVerdict) {
        this(trial, scores, gateVerdict, null);
    }

    public TrialResult {
        scores = List.copyOf(scores == null ? List.of() : scores);
    }

    public TrialVerdict verdict() {
        return trial.verdict();
    }
}

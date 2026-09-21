package com.h.backend.evaluation.domain;

import java.util.List;

public final class EvaluationGate {

    private final List<Score> scores;
    private final List<GraderRequirement> requirements;

    public EvaluationGate(List<Score> scores) {
        this(scores, List.of());
    }

    public EvaluationGate(List<Score> scores, List<GraderRequirement> requirements) {
        this.scores = List.copyOf(scores == null ? List.of() : scores);
        this.requirements = List.copyOf(requirements == null ? List.of() : requirements);
    }

    public GateVerdict verdict() {
        if (scores.isEmpty()) {
            return GateVerdict.INCONCLUSIVE;
        }
        boolean failed = false;
        boolean inconclusive = false;
        boolean requiredScoreSeen = false;

        for (GraderRequirement requirement : requirements) {
            Score score = scores.stream()
                    .filter(candidate -> candidate.graderId().equals(requirement.graderId())
                            && candidate.graderVersion().equals(requirement.graderVersion()))
                    .findFirst()
                    .orElse(null);
            requiredScoreSeen = true;
            if (score == null) {
                inconclusive = true;
                continue;
            }
            GateVerdict result = classify(score, requirement.threshold());
            failed |= result == GateVerdict.FAIL;
            inconclusive |= result == GateVerdict.INCONCLUSIVE;
        }

        for (Score score : scores) {
            if (!score.required() || declared(score)) {
                continue;
            }
            requiredScoreSeen = true;
            GateVerdict result = classify(score, score.threshold());
            failed |= result == GateVerdict.FAIL;
            inconclusive |= result == GateVerdict.INCONCLUSIVE;
        }

        if (failed) {
            return GateVerdict.FAIL;
        }
        return inconclusive || !requiredScoreSeen ? GateVerdict.INCONCLUSIVE : GateVerdict.PASS;
    }

    private boolean declared(Score score) {
        return requirements.stream().anyMatch(requirement ->
                requirement.graderId().equals(score.graderId())
                        && requirement.graderVersion().equals(score.graderVersion()));
    }

    private static GateVerdict classify(Score score, double threshold) {
        if (score.status() != ScoreStatus.OK || score.value() == null) {
            return GateVerdict.INCONCLUSIVE;
        }
        return score.value() >= threshold ? GateVerdict.PASS : GateVerdict.FAIL;
    }

    public List<Score> scores() {
        return scores;
    }
}

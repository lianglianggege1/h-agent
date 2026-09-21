package com.h.backend.evaluation.domain;

import java.util.List;

@FunctionalInterface
public interface EvaluationGrader {

    List<Score> grade(TrialEvidence evidence);
}

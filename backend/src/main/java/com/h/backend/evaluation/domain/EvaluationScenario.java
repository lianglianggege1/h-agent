package com.h.backend.evaluation.domain;

public interface EvaluationScenario {

    void prepare(EvaluationCase testCase);

    void execute(EvaluationCase testCase);

    TrialEvidence inspect(EvaluationCase testCase);

    void cleanup(EvaluationCase testCase);
}

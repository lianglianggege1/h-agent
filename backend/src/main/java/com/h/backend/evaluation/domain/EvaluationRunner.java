package com.h.backend.evaluation.domain;

import java.util.List;
import java.util.UUID;

public final class EvaluationRunner {

    private final EvaluationScenario scenario;
    private final EvaluationGrader grader;

    public EvaluationRunner(EvaluationScenario scenario, EvaluationGrader grader) {
        this.scenario = java.util.Objects.requireNonNull(scenario, "scenario");
        this.grader = java.util.Objects.requireNonNull(grader, "grader");
    }

    public TrialResult run(EvaluationCase testCase) {
        java.util.Objects.requireNonNull(testCase, "testCase");
        Trial trial = Trial.queued(UUID.randomUUID().toString(), testCase.caseId(), 0);
        List<Score> scores = List.of();
        GateVerdict gateVerdict = GateVerdict.INCONCLUSIVE;
        EvaluationOutcome outcome = EvaluationOutcome.INFRA_ERROR;
        TrialVerdict verdict = TrialVerdict.INCONCLUSIVE;
        String errorMessage = null;
        try {
            trial.transitionTo(TrialPhase.PREPARING);
            scenario.prepare(testCase);
            trial.transitionTo(TrialPhase.RUNNING);
            scenario.execute(testCase);
            trial.transitionTo(TrialPhase.EVALUATING);
            TrialEvidence evidence = scenario.inspect(testCase);
            outcome = evidence.outcome();
            if (outcome == EvaluationOutcome.SUCCEEDED) {
                scores = grader.grade(evidence);
                EvaluationGate gate = new EvaluationGate(scores, testCase.graders());
                gateVerdict = gate.verdict();
                verdict = verdictFor(outcome, gateVerdict);
            } else {
                verdict = verdictFor(outcome, GateVerdict.INCONCLUSIVE);
            }
        } catch (RuntimeException error) {
            errorMessage = messageOf(error);
            outcome = EvaluationOutcome.INFRA_ERROR;
            verdict = TrialVerdict.INCONCLUSIVE;
            gateVerdict = GateVerdict.INCONCLUSIVE;
        }

        if (trial.phase() != TrialPhase.CLEANING) {
            trial.transitionTo(TrialPhase.CLEANING);
        }
        try {
            scenario.cleanup(testCase);
        } catch (RuntimeException cleanupError) {
            errorMessage = messageOf(cleanupError);
            outcome = EvaluationOutcome.INFRA_ERROR;
            verdict = TrialVerdict.INCONCLUSIVE;
            gateVerdict = GateVerdict.INCONCLUSIVE;
        }
        trial.finish(outcome, verdict);
        return new TrialResult(trial, scores, gateVerdict, errorMessage);
    }

    private static TrialVerdict verdictFor(EvaluationOutcome outcome, GateVerdict gateVerdict) {
        return switch (outcome) {
            case CANCELLED -> TrialVerdict.NOT_EVALUATED;
            case INFRA_ERROR, INTERRUPTED -> TrialVerdict.INCONCLUSIVE;
            case FAILED, TIMEOUT -> TrialVerdict.FAIL;
            case SUCCEEDED -> switch (gateVerdict) {
                case PASS -> TrialVerdict.PASS;
                case FAIL -> TrialVerdict.FAIL;
                case INCONCLUSIVE -> TrialVerdict.INCONCLUSIVE;
            };
        };
    }

    private static String messageOf(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}

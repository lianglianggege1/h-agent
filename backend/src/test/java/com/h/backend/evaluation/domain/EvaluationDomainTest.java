package com.h.backend.evaluation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationDomainTest {

    @Test
    void trialMustFollowPreparationExecutionEvaluationAndCleanup() {
        Trial trial = Trial.queued("trial-1", "case-1", 0);

        trial.transitionTo(TrialPhase.PREPARING);
        trial.transitionTo(TrialPhase.RUNNING);
        trial.transitionTo(TrialPhase.EVALUATING);
        trial.transitionTo(TrialPhase.CLEANING);
        trial.finish(EvaluationOutcome.SUCCEEDED, TrialVerdict.PASS);

        assertEquals(TrialPhase.FINISHED, trial.phase());
        assertEquals(EvaluationOutcome.SUCCEEDED, trial.outcome());
        assertEquals(TrialVerdict.PASS, trial.verdict());
    }

    @Test
    void cannotFinishBeforeEvaluation() {
        Trial trial = Trial.queued("trial-1", "case-1", 0);
        trial.transitionTo(TrialPhase.PREPARING);

        assertThrows(IllegalStateException.class,
                () -> trial.finish(EvaluationOutcome.SUCCEEDED, TrialVerdict.PASS));
    }

    @Test
    void requiredFailureBlocksGateEvenWhenOtherScoresPass() {
        EvaluationGate gate = new EvaluationGate(List.of(
                new Score("state", "1", ScoreStatus.OK, 0.0, true),
                new Score("quality", "1", ScoreStatus.OK, 1.0, false)
        ));

        assertEquals(GateVerdict.FAIL, gate.verdict());
    }

    @Test
    void missingRequiredEvidenceIsInconclusive() {
        EvaluationGate gate = new EvaluationGate(List.of(
                new Score("state", "1", ScoreStatus.INSUFFICIENT_EVIDENCE, null, true)
        ));

        assertEquals(GateVerdict.INCONCLUSIVE, gate.verdict());
    }

    @Test
    void graderErrorIsInconclusiveInsteadOfAQualityFailure() {
        EvaluationGate gate = new EvaluationGate(List.of(
                new Score("state", "1", ScoreStatus.ERROR, null, true)
        ));

        assertEquals(GateVerdict.INCONCLUSIVE, gate.verdict());
    }

    @Test
    void requiredNotApplicableScoreIsInconclusive() {
        EvaluationGate gate = new EvaluationGate(List.of(
                new Score("state", "1", ScoreStatus.NOT_APPLICABLE, null, true)
        ));

        assertEquals(GateVerdict.INCONCLUSIVE, gate.verdict());
    }

    @Test
    void emptyScoresCannotPass() {
        assertEquals(GateVerdict.INCONCLUSIVE, new EvaluationGate(List.of()).verdict());
    }

    @Test
    void missingDeclaredGraderCannotPass() {
        EvaluationGate gate = new EvaluationGate(
                List.of(),
                List.of(new GraderRequirement("state", "1", 1.0))
        );

        assertEquals(GateVerdict.INCONCLUSIVE, gate.verdict());
    }

    @Test
    void requiredScoreUsesItsDeclaredThreshold() {
        EvaluationGate gate = new EvaluationGate(List.of(
                new Score("quality", "1", ScoreStatus.OK, 0.8, true, 0.75)
        ));

        assertEquals(GateVerdict.PASS, gate.verdict());
    }

    @Test
    void runnerGradesEvidenceAndCleansTheScenario() {
        RecordingScenario scenario = new RecordingScenario();
        EvaluationRunner runner = new EvaluationRunner(
                scenario,
                evidence -> List.of(new Score("state", "1", ScoreStatus.OK, 1.0, true))
        );

        TrialResult result = runner.run(
                new EvaluationCase(
                        "case-1", "bank-v1", "withdraw", Map.of("balance", 75),
                        List.of(new GraderRequirement("state", "1", 1.0))
                )
        );

        assertEquals(TrialVerdict.PASS, result.verdict());
        assertEquals(List.of("prepare", "execute", "inspect", "cleanup"), scenario.calls);
    }

    @Test
    void cancelledExecutionIsNotEvaluated() {
        RecordingScenario scenario = new RecordingScenario(EvaluationOutcome.CANCELLED, false);
        EvaluationRunner runner = new EvaluationRunner(scenario, evidence -> {
            throw new AssertionError("cancelled execution must not be graded");
        });

        TrialResult result = runner.run(
                new EvaluationCase("case-1", "bank-v1", "withdraw", Map.of(), List.of())
        );

        assertEquals(EvaluationOutcome.CANCELLED, result.trial().outcome());
        assertEquals(TrialVerdict.NOT_EVALUATED, result.verdict());
        assertEquals(GateVerdict.INCONCLUSIVE, result.gateVerdict());
    }

    @Test
    void cleanupFailureMakesTheTrialInconclusive() {
        RecordingScenario scenario = new RecordingScenario(EvaluationOutcome.SUCCEEDED, true);
        EvaluationRunner runner = new EvaluationRunner(
                scenario,
                evidence -> List.of(new Score("state", "1", ScoreStatus.OK, 1.0, true))
        );

        TrialResult result = runner.run(
                new EvaluationCase(
                        "case-1", "bank-v1", "withdraw", Map.of(),
                        List.of(new GraderRequirement("state", "1", 1.0))
                )
        );

        assertEquals(TrialPhase.FINISHED, result.trial().phase());
        assertEquals(EvaluationOutcome.INFRA_ERROR, result.trial().outcome());
        assertEquals(TrialVerdict.INCONCLUSIVE, result.verdict());
        assertEquals("cleanup failed", result.errorMessage());
    }

    private static final class RecordingScenario implements EvaluationScenario {
        private final java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        private final EvaluationOutcome outcome;
        private final boolean failCleanup;

        private RecordingScenario() {
            this(EvaluationOutcome.SUCCEEDED, false);
        }

        private RecordingScenario(EvaluationOutcome outcome, boolean failCleanup) {
            this.outcome = outcome;
            this.failCleanup = failCleanup;
        }

        @Override
        public void prepare(EvaluationCase testCase) { calls.add("prepare"); }

        @Override
        public void execute(EvaluationCase testCase) { calls.add("execute"); }

        @Override
        public TrialEvidence inspect(EvaluationCase testCase) {
            calls.add("inspect");
            return new TrialEvidence(outcome, Map.of("balance", 75));
        }

        @Override
        public void cleanup(EvaluationCase testCase) {
            calls.add("cleanup");
            if (failCleanup) {
                throw new IllegalStateException("cleanup failed");
            }
        }
    }
}

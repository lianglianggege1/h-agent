package com.h.backend.evaluation.domain;

import java.util.EnumSet;
import java.util.Set;

public final class Trial {

    private static final Set<TrialPhase> TERMINAL = EnumSet.of(TrialPhase.FINISHED);

    private final String id;
    private final String caseId;
    private final int repeatIndex;
    private TrialPhase phase;
    private EvaluationOutcome outcome;
    private TrialVerdict verdict;

    private Trial(String id, String caseId, int repeatIndex) {
        if (id == null || id.isBlank() || caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("trial id and case id are required");
        }
        if (repeatIndex < 0) {
            throw new IllegalArgumentException("repeatIndex must be non-negative");
        }
        this.id = id;
        this.caseId = caseId;
        this.repeatIndex = repeatIndex;
        this.phase = TrialPhase.QUEUED;
        this.verdict = TrialVerdict.NOT_EVALUATED;
    }

    public static Trial queued(String id, String caseId, int repeatIndex) {
        return new Trial(id, caseId, repeatIndex);
    }

    public void transitionTo(TrialPhase next) {
        if (next == null || TERMINAL.contains(phase)) {
            throw new IllegalStateException("trial is already finished");
        }
        if (!allowed(phase, next)) {
            throw new IllegalStateException("invalid trial transition: " + phase + " -> " + next);
        }
        phase = next;
    }

    public void finish(EvaluationOutcome outcome, TrialVerdict verdict) {
        if (phase != TrialPhase.CLEANING) {
            throw new IllegalStateException("trial must be cleaning before finish");
        }
        if (outcome == null || verdict == null) {
            throw new IllegalArgumentException("outcome and verdict are required");
        }
        this.outcome = outcome;
        this.verdict = verdict;
        this.phase = TrialPhase.FINISHED;
    }

    private static boolean allowed(TrialPhase from, TrialPhase to) {
        return switch (from) {
            case QUEUED -> to == TrialPhase.PREPARING;
            case PREPARING -> to == TrialPhase.RUNNING || to == TrialPhase.CLEANING;
            case RUNNING -> to == TrialPhase.WAITING_INTERACTION
                    || to == TrialPhase.EVALUATING || to == TrialPhase.CLEANING;
            case WAITING_INTERACTION -> to == TrialPhase.RUNNING
                    || to == TrialPhase.EVALUATING || to == TrialPhase.CLEANING;
            case EVALUATING -> to == TrialPhase.CLEANING;
            case CLEANING, FINISHED -> false;
        };
    }

    public String id() { return id; }
    public String caseId() { return caseId; }
    public int repeatIndex() { return repeatIndex; }
    public TrialPhase phase() { return phase; }
    public EvaluationOutcome outcome() { return outcome; }
    public TrialVerdict verdict() { return verdict; }
}

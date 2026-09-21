package com.h.backend.evaluation.domain;

public enum TrialPhase {
    QUEUED,
    PREPARING,
    RUNNING,
    WAITING_INTERACTION,
    EVALUATING,
    CLEANING,
    FINISHED
}

package com.h.backend.evaluation.domain;

public enum EvaluationOutcome {
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    CANCELLED,
    INFRA_ERROR,
    INTERRUPTED
}

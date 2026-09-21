CREATE TABLE eval_dataset_snapshots (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    source VARCHAR(32) NOT NULL,
    source_version VARCHAR(255),
    schema_version INTEGER NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    items_json JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_eval_dataset_snapshot_source CHECK (source IN ('LANGFUSE', 'LOCAL_JSON')),
    CONSTRAINT uk_eval_dataset_snapshot_content UNIQUE (owner_user_id, content_hash)
);

CREATE TABLE eval_experiments (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id),
    request_id VARCHAR(128) NOT NULL,
    snapshot_id BIGINT NOT NULL REFERENCES eval_dataset_snapshots(id),
    name VARCHAR(255) NOT NULL,
    manifest_json JSONB NOT NULL,
    baseline_id BIGINT REFERENCES eval_experiments(id),
    status VARCHAR(32) NOT NULL,
    gate_verdict VARCHAR(16),
    cancel_requested_at TIMESTAMP,
    report_hash VARCHAR(128),
    origin VARCHAR(32) NOT NULL DEFAULT 'LOCAL',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_eval_experiment_request UNIQUE (owner_user_id, request_id),
    CONSTRAINT ck_eval_experiment_status CHECK (status IN ('READY', 'RUNNING', 'STOPPING', 'FINISHED', 'CANCELLED', 'BLOCKED')),
    CONSTRAINT ck_eval_experiment_gate CHECK (gate_verdict IS NULL OR gate_verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    CONSTRAINT ck_eval_experiment_origin CHECK (origin IN ('LOCAL', 'IMPORTED'))
);

CREATE UNIQUE INDEX uk_eval_active_experiment
    ON eval_experiments ((1))
    WHERE status IN ('RUNNING', 'STOPPING');

CREATE TABLE eval_trials (
    id BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES eval_experiments(id) ON DELETE CASCADE,
    case_id VARCHAR(255) NOT NULL,
    repeat_index INTEGER NOT NULL,
    phase VARCHAR(32) NOT NULL,
    execution_outcome VARCHAR(32),
    verdict VARCHAR(24) NOT NULL DEFAULT 'NOT_EVALUATED',
    session_refs_json JSONB NOT NULL DEFAULT '[]',
    run_refs_json JSONB NOT NULL DEFAULT '[]',
    evidence_json JSONB,
    evidence_ref VARCHAR(1024),
    metrics_json JSONB NOT NULL DEFAULT '{}',
    cleanup_status VARCHAR(16) NOT NULL DEFAULT 'NOT_STARTED',
    error_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_eval_trial_case_repeat UNIQUE (experiment_id, case_id, repeat_index),
    CONSTRAINT ck_eval_trial_phase CHECK (phase IN ('QUEUED', 'PREPARING', 'RUNNING', 'WAITING_INTERACTION', 'EVALUATING', 'CLEANING', 'FINISHED')),
    CONSTRAINT ck_eval_trial_outcome CHECK (execution_outcome IS NULL OR execution_outcome IN ('SUCCEEDED', 'FAILED', 'TIMEOUT', 'CANCELLED', 'INFRA_ERROR', 'INTERRUPTED')),
    CONSTRAINT ck_eval_trial_verdict CHECK (verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE', 'NOT_EVALUATED')),
    CONSTRAINT ck_eval_trial_cleanup CHECK (cleanup_status IN ('NOT_STARTED', 'RUNNING', 'DONE', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_eval_trial_repeat CHECK (repeat_index >= 0)
);

CREATE INDEX idx_eval_trials_experiment_phase ON eval_trials(experiment_id, phase);

CREATE TABLE eval_scores (
    id BIGSERIAL PRIMARY KEY,
    trial_id BIGINT NOT NULL REFERENCES eval_trials(id) ON DELETE CASCADE,
    grader_id VARCHAR(255) NOT NULL,
    grader_version VARCHAR(64) NOT NULL,
    score_revision INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    value DOUBLE PRECISION,
    reason TEXT,
    required BOOLEAN NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    revision_source VARCHAR(32) NOT NULL,
    evidence_refs JSONB NOT NULL DEFAULT '[]',
    judge_manifest JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_eval_score_revision UNIQUE (trial_id, grader_id, grader_version, score_revision),
    CONSTRAINT ck_eval_score_status CHECK (status IN ('OK', 'ERROR', 'INSUFFICIENT_EVIDENCE', 'NOT_APPLICABLE')),
    CONSTRAINT ck_eval_score_value CHECK (value IS NULL OR value >= 0 AND value <= 1),
    CONSTRAINT ck_eval_score_threshold CHECK (threshold >= 0 AND threshold <= 1),
    CONSTRAINT ck_eval_score_reason CHECK (value IS NOT NULL OR reason IS NOT NULL),
    CONSTRAINT ck_eval_score_revision_source CHECK (revision_source IN ('AUTOMATED', 'HUMAN', 'RESCORE', 'IMPORTED'))
);

CREATE INDEX idx_eval_scores_trial ON eval_scores(trial_id);

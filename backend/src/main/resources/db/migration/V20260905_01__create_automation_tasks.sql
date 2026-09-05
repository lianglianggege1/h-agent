CREATE TABLE IF NOT EXISTS automation_tasks (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    instruction TEXT NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    runtime VARCHAR(32) NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    last_status VARCHAR(32),
    created_via VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_automation_tasks_due
    ON automation_tasks(enabled, next_run_at, lease_until)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_automation_tasks_owner
    ON automation_tasks(user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS automation_runs (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES automation_tasks(id),
    user_id BIGINT NOT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    scheduled_for TIMESTAMP,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    session_id VARCHAR(64),
    output TEXT,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_automation_runs_task_started
    ON automation_runs(task_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_automation_runs_owner_started
    ON automation_runs(user_id, started_at DESC);

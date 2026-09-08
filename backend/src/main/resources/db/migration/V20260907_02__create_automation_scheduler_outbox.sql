CREATE TABLE IF NOT EXISTS automation_scheduler_projection (
    task_id VARCHAR(64) PRIMARY KEY REFERENCES automation_tasks(id),
    desired_revision BIGINT NOT NULL,
    desired_state VARCHAR(16) NOT NULL,
    xxl_job_id BIGINT,
    synced_revision BIGINT,
    sync_status VARCHAR(24) NOT NULL,
    last_error VARCHAR(500),
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS automation_scheduler_outbox (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES automation_tasks(id),
    task_revision BIGINT NOT NULL,
    desired_state VARCHAR(16) NOT NULL,
    task_name VARCHAR(120) NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    zone_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP NOT NULL,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_automation_scheduler_event UNIQUE(task_id, task_revision, desired_state)
);

CREATE INDEX IF NOT EXISTS idx_automation_scheduler_outbox_due
    ON automation_scheduler_outbox(status, available_at, lease_until);

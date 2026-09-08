ALTER TABLE automation_runs
    ADD COLUMN IF NOT EXISTS task_revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS trigger_id VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_automation_scheduled_run
    ON automation_runs(task_id, task_revision, scheduled_for, trigger_type)
    WHERE trigger_type = 'SCHEDULED';

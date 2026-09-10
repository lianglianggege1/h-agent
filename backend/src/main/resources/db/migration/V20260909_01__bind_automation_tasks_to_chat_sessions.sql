ALTER TABLE automation_tasks
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(64) NOT NULL;

ALTER TABLE automation_tasks
    ALTER COLUMN session_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_automation_tasks_session
    ON automation_tasks(session_id)
    WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS generation_tasks (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    generation_type VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    spec_json TEXT NOT NULL,
    provider_task_id VARCHAR(128),
    provider_status VARCHAR(64),
    provider_file_id VARCHAR(128),
    chat_message_id BIGINT,
    artifact_id VARCHAR(64),
    artifact_storage_type VARCHAR(32),
    artifact_storage_key VARCHAR(512),
    artifact_mime_type VARCHAR(128),
    artifact_file_name VARCHAR(255),
    artifact_size BIGINT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_poll_at TIMESTAMP,
    failure_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_generation_tasks_due
    ON generation_tasks(status, next_poll_at);

CREATE INDEX IF NOT EXISTS idx_generation_tasks_user_created
    ON generation_tasks(user_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_generation_tasks_provider_task
    ON generation_tasks(provider_task_id)
    WHERE provider_task_id IS NOT NULL;

CREATE TABLE a2a_document_steps (
    task_id VARCHAR(64) NOT NULL REFERENCES chat_document_tasks(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    callback_requested BOOLEAN NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    notification_json TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    lease_token VARCHAR(36),
    lease_until TIMESTAMPTZ,
    PRIMARY KEY (task_id, revision)
);
CREATE INDEX a2a_document_steps_pending ON a2a_document_steps (available_at)
    WHERE state IN ('PENDING', 'NOTIFY');

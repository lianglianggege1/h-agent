CREATE TABLE a2a_document_notifications (
    task_id VARCHAR(64) PRIMARY KEY REFERENCES chat_document_tasks (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    state VARCHAR(40) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

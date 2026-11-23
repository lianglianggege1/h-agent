CREATE TABLE document_canvases (
    session_id VARCHAR(255) PRIMARY KEY REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    revision BIGINT NOT NULL,
    content TEXT NOT NULL,
    source_task_id VARCHAR(64),
    source_resource_id VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

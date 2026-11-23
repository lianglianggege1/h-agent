CREATE TABLE chat_a2a_story_editions (
    session_id VARCHAR(255) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    task_id VARCHAR(128) NOT NULL,
    context_id VARCHAR(128) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, task_id)
);
CREATE INDEX chat_a2a_story_editions_latest ON chat_a2a_story_editions (session_id, completed_at DESC);

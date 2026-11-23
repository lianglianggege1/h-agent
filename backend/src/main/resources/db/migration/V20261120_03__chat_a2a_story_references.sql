CREATE TABLE chat_a2a_story_references (
    session_id VARCHAR(255) PRIMARY KEY REFERENCES agent_sessions (session_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users (id),
    task_id VARCHAR(128) NOT NULL,
    context_id VARCHAR(128) NOT NULL
);

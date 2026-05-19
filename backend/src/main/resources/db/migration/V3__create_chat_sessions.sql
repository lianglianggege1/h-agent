CREATE TABLE IF NOT EXISTS chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    prompt_id BIGINT,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_user_message TEXT,
    message_count INTEGER NOT NULL DEFAULT 0,
    last_active_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_chat_sessions_user_id FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_chat_sessions_prompt_id FOREIGN KEY (prompt_id) REFERENCES system_prompts(id),
    CONSTRAINT uk_chat_sessions_session_id UNIQUE (session_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_status_updated_at ON chat_sessions(user_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_updated_at ON chat_sessions(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS agent_runs (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    prompt_id BIGINT,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    langfuse_trace_id VARCHAR(128) NULL,
    tool_count INTEGER NOT NULL DEFAULT 0,
    tool_names_json TEXT NOT NULL DEFAULT '[]',
    error_message TEXT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_agent_runs_user_message_id
        FOREIGN KEY (user_message_id) REFERENCES chat_session_messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_runs_assistant_message_id
        FOREIGN KEY (assistant_message_id) REFERENCES chat_session_messages(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_session_started_at
    ON agent_runs(session_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_runs_user_started_at
    ON agent_runs(user_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_runs_assistant_message_id
    ON agent_runs(assistant_message_id);

CREATE INDEX IF NOT EXISTS idx_agent_runs_langfuse_trace_id
    ON agent_runs(langfuse_trace_id);

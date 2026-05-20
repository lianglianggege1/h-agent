CREATE TABLE IF NOT EXISTS chat_memory_snapshots (
    id BIGSERIAL PRIMARY KEY,
    session_record_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    prompt_id BIGINT,
    memory_payload_json TEXT NOT NULL,
    memory_format VARCHAR(64) NOT NULL,
    window_size INTEGER NOT NULL,
    source_message_count INTEGER NOT NULL DEFAULT 0,
    snapshot_version BIGINT NOT NULL,
    last_compacted_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_memory_snapshots_session_record_id
        FOREIGN KEY (session_record_id) REFERENCES chat_sessions(id) ON DELETE CASCADE,
    CONSTRAINT uk_chat_memory_snapshots_session_record_id UNIQUE (session_record_id),
    CONSTRAINT uk_chat_memory_snapshots_session_id UNIQUE (session_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_memory_snapshots_user_updated_at
    ON chat_memory_snapshots(user_id, updated_at DESC);

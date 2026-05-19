CREATE TABLE IF NOT EXISTS chat_session_messages (
    id BIGSERIAL PRIMARY KEY,
    session_record_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    sequence_no INTEGER NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    content_text TEXT,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_session_messages_record_id FOREIGN KEY (session_record_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_session_messages_record_seq
    ON chat_session_messages(session_record_id, sequence_no);

CREATE INDEX IF NOT EXISTS idx_chat_session_messages_session_id
    ON chat_session_messages(session_id, sequence_no);

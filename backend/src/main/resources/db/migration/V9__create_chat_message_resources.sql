CREATE TABLE IF NOT EXISTS chat_message_resources (
    id VARCHAR(64) PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    resource_kind VARCHAR(32) NOT NULL,
    storage_type VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    view_url VARCHAR(512) NOT NULL,
    download_url VARCHAR(512) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_size BIGINT,
    width INTEGER,
    height INTEGER,
    sha256 VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_message_resources_message_id
        FOREIGN KEY (message_id) REFERENCES chat_session_messages(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_message_resources_message_id
    ON chat_message_resources(message_id);

CREATE INDEX IF NOT EXISTS idx_chat_message_resources_session_id
    ON chat_message_resources(session_id, created_at DESC);

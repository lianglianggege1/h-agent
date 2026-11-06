CREATE TABLE chat_document_settings (
    user_id BIGINT NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    external_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, session_id)
);
CREATE TABLE chat_document_tasks (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(160) NOT NULL,
    resource_id VARCHAR(64),
    request_key VARCHAR(128) NOT NULL,
    status VARCHAR(40) NOT NULL,
    state_json TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, session_id, request_key)
);
CREATE INDEX chat_document_tasks_queue ON chat_document_tasks(status, lease_until);
CREATE INDEX chat_document_tasks_session ON chat_document_tasks(user_id, session_id, created_at);

ALTER TABLE chat_message_resources ADD COLUMN file_extension VARCHAR(32);
UPDATE chat_message_resources SET file_extension=LOWER(SUBSTRING(file_name FROM '\.([A-Za-z0-9]{1,16})$'));

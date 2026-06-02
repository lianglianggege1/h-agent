CREATE TABLE IF NOT EXISTS knowledge_document (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    prompt_id     BIGINT       NOT NULL,
    file_name     VARCHAR(512) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,
    file_type     VARCHAR(32),
    file_size     BIGINT,
    char_count    INTEGER,
    segment_count INTEGER,
    status        VARCHAR(16)  NOT NULL,
    error_msg     TEXT,
    content_hash  VARCHAR(64),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_doc_user_prompt
    ON knowledge_document(user_id, prompt_id);

CREATE TABLE knowledge_edit_drafts (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    base_version INTEGER NOT NULL,
    body_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX knowledge_edit_drafts_owner ON knowledge_edit_drafts (user_id, document_id, created_at DESC);

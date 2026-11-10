-- Knowledge belongs to users, independently of prompts. Preserve document IDs and vectors.
DROP INDEX IF EXISTS idx_knowledge_doc_user_prompt;
ALTER TABLE knowledge_document DROP COLUMN prompt_id;
CREATE INDEX idx_knowledge_doc_user ON knowledge_document(user_id, id DESC);
ALTER TABLE knowledge_document ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE knowledge_document ADD COLUMN body TEXT;
ALTER TABLE knowledge_document ADD COLUMN applicability TEXT NOT NULL DEFAULT '';
ALTER TABLE knowledge_document ADD COLUMN source_refs TEXT NOT NULL DEFAULT '[]';
ALTER TABLE knowledge_document ADD COLUMN pending_operation VARCHAR(64);
CREATE TABLE knowledge_operations (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    document_id BIGINT REFERENCES knowledge_document(id) ON DELETE SET NULL,
    target_version INTEGER,
    state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    result TEXT,
    lease_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_knowledge_operations_pending ON knowledge_operations(state, updated_at);
CREATE TABLE knowledge_revisions (
    document_id BIGINT NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    title VARCHAR(512) NOT NULL,
    body TEXT NOT NULL,
    applicability TEXT NOT NULL,
    source_refs TEXT NOT NULL,
    actor TEXT NOT NULL,
    session_id TEXT NOT NULL,
    execution_id TEXT NOT NULL,
    operation_id VARCHAR(64) NOT NULL REFERENCES knowledge_operations(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY(document_id, version)
);
-- On a fresh install the vector table is created later by PgVectorEmbeddingStore.
DO $$ BEGIN
    IF to_regclass('knowledge_embeddings') IS NOT NULL THEN
        EXECUTE $sql$UPDATE knowledge_embeddings SET metadata = metadata - 'promptId' WHERE metadata ? 'promptId'$sql$;
    END IF;
END $$;

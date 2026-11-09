-- Active semantic text stays in Mem0; immutable revisions support history and recovery.
ALTER TABLE long_term_memory_records ADD COLUMN memory_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE long_term_memory_records ADD COLUMN successor_id BIGINT REFERENCES long_term_memory_records(id);
ALTER TABLE long_term_memory_records ADD COLUMN source_refs TEXT NOT NULL DEFAULT '[]';
ALTER TABLE long_term_memory_records ADD COLUMN pending_operation VARCHAR(64);
UPDATE long_term_memory_records SET memory_status = 'DELETED' WHERE deleted_at IS NOT NULL;
COMMENT ON COLUMN long_term_memory_records.scope_kind IS 'USER | AGENT | RUN (legacy) | SESSION';
CREATE TABLE agent_memory_revisions (
    memory_id BIGINT NOT NULL REFERENCES long_term_memory_records(id),
    version INTEGER NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    successor_id BIGINT,
    source_refs TEXT NOT NULL,
    actor TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY(memory_id, version)
);
CREATE TABLE agent_memory_mutations (
    id VARCHAR(64) PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'NEW',
    memory_id BIGINT REFERENCES long_term_memory_records(id),
    remote_memory_id VARCHAR(128),
    result TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_agent_memory_mutations_pending ON agent_memory_mutations(state, updated_at);
CREATE INDEX idx_ltm_session ON long_term_memory_records(owner_user_id, scope_kind, memory_run_id, memory_status);

ALTER TABLE agent_memory_revisions ADD COLUMN operation_id VARCHAR(64) REFERENCES agent_memory_mutations(id);

-- Do not backfill standard-chat turns queued by the previous automatic pipeline.
UPDATE long_term_memory_capture_outbox
SET state='DEAD_LETTER', last_error='Automatic standard-chat capture disabled by model-directed memory rollout', updated_at=NOW()
WHERE logical_agent_id='standard-chat' AND state IN ('PENDING','PROCESSING','RECONCILING');

-- Product progress snapshots; deliberately not an append-only token/event log.
CREATE TABLE chat_interaction_runs (
    run_id VARCHAR(128) PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    snapshot_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_chat_interaction_runs_session ON chat_interaction_runs(user_id, session_id, created_at);
CREATE UNIQUE INDEX idx_chat_interaction_runs_active ON chat_interaction_runs(session_id)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');

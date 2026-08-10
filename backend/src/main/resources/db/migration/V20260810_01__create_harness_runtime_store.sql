CREATE SCHEMA IF NOT EXISTS agentscope;

CREATE TABLE IF NOT EXISTS agentscope.agent_state_snapshots (
    session_id VARCHAR(255) NOT NULL,
    state_key VARCHAR(255) NOT NULL,
    item_index INTEGER NOT NULL DEFAULT 0,
    state_data TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
);

CREATE TABLE IF NOT EXISTS agentscope.workspace_files (
    namespace_path VARCHAR(2048) NOT NULL,
    item_key VARCHAR(255) NOT NULL,
    value_json TEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (namespace_path, item_key)
);

CREATE INDEX IF NOT EXISTS idx_harness_workspace_updated_at
    ON agentscope.workspace_files (updated_at);

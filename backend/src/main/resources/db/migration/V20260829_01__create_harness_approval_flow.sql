ALTER TABLE agent_sessions
    ADD COLUMN approval_mode VARCHAR(32);

WITH RECURSIVE harness_session_tree AS (
    SELECT session_id
    FROM agent_sessions
    WHERE agent_id = 'harness-agent'
    UNION ALL
    SELECT child.session_id
    FROM agent_sessions child
    JOIN harness_session_tree parent
      ON child.parent_session_id = parent.session_id
)
UPDATE agent_sessions
SET approval_mode = 'BYPASS'
WHERE session_id IN (SELECT session_id FROM harness_session_tree);

ALTER TABLE agent_sessions
    ADD CONSTRAINT ck_agent_sessions_approval_mode CHECK (
        approval_mode IS NULL OR approval_mode IN (
            'DEFAULT', 'ACCEPT_EDITS', 'EXPLORE', 'BYPASS', 'DONT_ASK'
        )
    );

ALTER TABLE agent_runs
    ADD COLUMN approval_mode_snapshot VARCHAR(32),
    ADD COLUMN trace_parent VARCHAR(128);

CREATE UNIQUE INDEX uk_agent_runs_open_session
    ON agent_runs(session_id)
    WHERE status IN ('RUNNING', 'WAITING_APPROVAL');

CREATE TABLE approval_requests (
    approval_id VARCHAR(36) PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id),
    root_session_id VARCHAR(255) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    session_id VARCHAR(255) NOT NULL REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    request_key VARCHAR(255) NOT NULL,
    reply_id VARCHAR(255),
    subagent_execution_id VARCHAR(255),
    approval_mode VARCHAR(32) NOT NULL,
    tool_call_ids_json JSONB NOT NULL,
    tool_names_json JSONB NOT NULL,
    display_items_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    decision VARCHAR(16),
    version INTEGER NOT NULL DEFAULT 0,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    decided_at TIMESTAMP,
    decided_by BIGINT REFERENCES users(id),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_approval_requests_run_request UNIQUE (run_id, request_key),
    CONSTRAINT ck_approval_requests_status CHECK (
        status IN ('PENDING', 'APPROVED', 'DENIED', 'CANCELLED')
    ),
    CONSTRAINT ck_approval_requests_decision CHECK (
        decision IS NULL OR decision IN ('APPROVE', 'DENY')
    ),
    CONSTRAINT ck_approval_requests_mode CHECK (
        approval_mode IN ('DEFAULT', 'ACCEPT_EDITS', 'EXPLORE', 'BYPASS', 'DONT_ASK')
    ),
    CONSTRAINT ck_approval_requests_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uk_approval_requests_pending_session
    ON approval_requests(session_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_approval_requests_user_session
    ON approval_requests(user_id, session_id, requested_at DESC);

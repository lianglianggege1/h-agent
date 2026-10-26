-- Order assistant execution requests are separate from generic Harness state.
CREATE TABLE order_execution_jobs (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_json TEXT NOT NULL,
    operator BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    result_json TEXT,
    error_message VARCHAR(500),
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX order_execution_jobs_pending ON order_execution_jobs(status,lease_until,created_at);
CREATE INDEX order_execution_jobs_session ON order_execution_jobs(user_id,session_id,created_at);

CREATE TABLE order_chat_recovery (
    run_id VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX order_chat_recovery_pending ON order_chat_recovery(status,updated_at);

-- Run 终态与投递解耦：每个目的地一条投递意图，独立重试与死信。
CREATE TABLE IF NOT EXISTS automation_deliveries (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES automation_runs(id),
    task_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    sink_type VARCHAR(16) NOT NULL,
    target_json TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_until TIMESTAMP,
    last_error VARCHAR(1000),
    delivered_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- SESSION 由平台控制：同一 Run 的会话投递至多一条，崩溃重放不重复建单。
CREATE UNIQUE INDEX IF NOT EXISTS uq_automation_delivery_session
    ON automation_deliveries(run_id, sink_type)
    WHERE sink_type = 'SESSION';

CREATE INDEX IF NOT EXISTS idx_automation_deliveries_due
    ON automation_deliveries(status, next_attempt_at, lease_until);

CREATE INDEX IF NOT EXISTS idx_automation_deliveries_run
    ON automation_deliveries(run_id);

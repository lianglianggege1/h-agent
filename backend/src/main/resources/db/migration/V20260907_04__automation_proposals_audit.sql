-- 聊天内写操作先生成提案；提案 24 小时内有效，确认时重新校验所有权与基线版本。
CREATE TABLE IF NOT EXISTS automation_proposals (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id VARCHAR(64),
    action VARCHAR(24) NOT NULL,
    payload_json TEXT NOT NULL,
    base_task_revision BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    source_session_id VARCHAR(64),
    created_via VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    result_task_id VARCHAR(64),
    expires_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    confirmed_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_automation_proposal_idempotency UNIQUE(user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_automation_proposals_owner_status
    ON automation_proposals(user_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_automation_proposals_expiry
    ON automation_proposals(status, expires_at);

-- 自动化领域审计：任务/提案/投递的关键状态变化均留痕。
CREATE TABLE IF NOT EXISTS automation_audit_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    action VARCHAR(48) NOT NULL,
    target_type VARCHAR(24),
    target_id VARCHAR(64),
    before_revision BIGINT,
    after_revision BIGINT,
    request_key VARCHAR(128),
    detail_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_automation_audit_target
    ON automation_audit_events(target_type, target_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_automation_audit_owner
    ON automation_audit_events(user_id, created_at DESC);

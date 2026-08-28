-- 本地控制索引：不保存记忆正文。正文与语义演化存储在 Mem0，
-- 本地只保留 owner、scope、版本、操作状态与幂等信息。

CREATE TABLE IF NOT EXISTS long_term_memory_records (
    id BIGSERIAL PRIMARY KEY,
    remote_memory_id VARCHAR(128) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    logical_agent_id VARCHAR(64) NULL,
    memory_run_id VARCHAR(64) NULL,
    version INTEGER NOT NULL DEFAULT 1,
    operation_state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    source VARCHAR(24) NOT NULL,
    source_execution_id BIGINT NULL,
    remote_hash VARCHAR(64) NULL,
    remote_updated_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP NULL
);

COMMENT ON COLUMN long_term_memory_records.scope_kind IS 'USER | AGENT | RUN';
COMMENT ON COLUMN long_term_memory_records.logical_agent_id IS '稳定逻辑 Agent ID；USER scope 为空';
COMMENT ON COLUMN long_term_memory_records.memory_run_id IS '稳定逻辑任务 ID（rootSessionId）；USER/AGENT scope 为空';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_records_remote_memory_id
    ON long_term_memory_records(remote_memory_id);

CREATE INDEX IF NOT EXISTS idx_ltm_records_owner_scope
    ON long_term_memory_records(owner_user_id, scope_kind, deleted_at);

CREATE INDEX IF NOT EXISTS idx_ltm_records_owner_agent
    ON long_term_memory_records(owner_user_id, logical_agent_id, deleted_at);

CREATE INDEX IF NOT EXISTS idx_ltm_records_owner_agent_run
    ON long_term_memory_records(owner_user_id, logical_agent_id, memory_run_id, deleted_at);

-- capture outbox：成功 turn 与消息/run 同事务入队，worker 异步投递 Mem0。
CREATE TABLE IF NOT EXISTS long_term_memory_capture_outbox (
    id BIGSERIAL PRIMARY KEY,
    operation_key VARCHAR(320) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    logical_agent_id VARCHAR(64) NOT NULL,
    memory_run_id VARCHAR(64) NOT NULL,
    scope_kind VARCHAR(8) NOT NULL,
    source_execution_id BIGINT NOT NULL,
    prompt_id BIGINT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN long_term_memory_capture_outbox.operation_key IS '{userId}:{logicalAgentId}:{memoryRunId}:{sourceExecutionId}:long-term-memory-capture:v1';
COMMENT ON COLUMN long_term_memory_capture_outbox.state IS 'PENDING | PROCESSING | COMPLETED | RECONCILING | DEAD_LETTER';
COMMENT ON COLUMN long_term_memory_capture_outbox.user_message_id IS 'worker 从已持久化消息回读正文，outbox 不保存消息副本';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_capture_outbox_operation_key
    ON long_term_memory_capture_outbox(operation_key);

CREATE INDEX IF NOT EXISTS idx_ltm_capture_outbox_state_next_attempt
    ON long_term_memory_capture_outbox(state, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_ltm_capture_outbox_owner
    ON long_term_memory_capture_outbox(owner_user_id, created_at DESC);

-- mutation saga：显式 save/update/delete 与 reconciliation 的操作记录。
CREATE TABLE IF NOT EXISTS long_term_memory_operations (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    remote_memory_id VARCHAR(128) NULL,
    operation_kind VARCHAR(24) NOT NULL,
    operation_key VARCHAR(320) NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    last_error TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN long_term_memory_operations.operation_kind IS 'EXPLICIT_SAVE | EXPLICIT_UPDATE | EXPLICIT_DELETE | AUTO_CAPTURE | RECONCILIATION';
COMMENT ON COLUMN long_term_memory_operations.state IS 'PENDING | SUCCEEDED | FAILED | RECONCILING | DEAD_LETTER';

CREATE INDEX IF NOT EXISTS idx_ltm_operations_owner
    ON long_term_memory_operations(owner_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ltm_operations_key
    ON long_term_memory_operations(operation_key);

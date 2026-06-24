ALTER TABLE chat_memory_snapshots
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(64) NOT NULL DEFAULT 'standard-chat',
    ADD COLUMN IF NOT EXISTS memory_scope VARCHAR(128) NOT NULL DEFAULT 'default';

ALTER TABLE chat_memory_snapshots
    DROP CONSTRAINT IF EXISTS uk_chat_memory_snapshots_session_record_id,
    DROP CONSTRAINT IF EXISTS uk_chat_memory_snapshots_session_id;

DROP INDEX IF EXISTS uk_chat_memory_snapshots_session_record_id;
DROP INDEX IF EXISTS uk_chat_memory_snapshots_session_id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_chat_memory_snapshots_session_agent_scope
    ON chat_memory_snapshots(session_id, agent_id, memory_scope);

CREATE INDEX IF NOT EXISTS idx_chat_memory_snapshots_session_id
    ON chat_memory_snapshots(session_id);

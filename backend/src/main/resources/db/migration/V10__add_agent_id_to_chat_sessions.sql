ALTER TABLE chat_sessions
    ADD COLUMN IF NOT EXISTS agent_id VARCHAR(64);

UPDATE chat_sessions
SET agent_id = 'standard-chat'
WHERE agent_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_agent_status_updated_at
    ON chat_sessions(user_id, agent_id, status, updated_at DESC);

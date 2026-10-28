-- Superseded product messages retain execution/resource foreign keys for audit and async delivery.
ALTER TABLE chat_session_messages ADD COLUMN superseded_at TIMESTAMP;
DROP INDEX idx_chat_interaction_runs_active;
CREATE UNIQUE INDEX idx_chat_interaction_runs_active ON chat_interaction_runs(session_id)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED', 'PAUSE_REQUESTED');

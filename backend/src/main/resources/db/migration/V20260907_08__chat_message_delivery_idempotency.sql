ALTER TABLE chat_session_messages
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(160);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_session_message_idempotency
    ON chat_session_messages(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

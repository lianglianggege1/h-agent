ALTER TABLE chat_message_resources
    ALTER COLUMN message_id DROP NOT NULL;

ALTER TABLE chat_message_resources
    DROP COLUMN IF EXISTS sha256;

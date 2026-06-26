ALTER TABLE chat_message_resources
    ADD COLUMN IF NOT EXISTS resource_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS resource_role VARCHAR(32),
    ADD COLUMN IF NOT EXISTS metadata_json TEXT;

UPDATE chat_message_resources
SET resource_type = COALESCE(resource_type, resource_kind),
    resource_role = COALESCE(resource_role, 'ATTACHMENT')
WHERE resource_type IS NULL
   OR resource_role IS NULL;

ALTER TABLE chat_message_resources
    ALTER COLUMN resource_type SET NOT NULL,
    ALTER COLUMN resource_role SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_chat_message_resources_type_role
    ON chat_message_resources(resource_type, resource_role);

ALTER TABLE chat_message_resources
    DROP COLUMN IF EXISTS resource_kind;

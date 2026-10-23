-- Keep deleted prompt identities/content available to historical sessions and runs.
ALTER TABLE system_prompts ADD COLUMN deleted_at TIMESTAMP;

-- Concurrent first visits could create identical defaults. Retain their referenced rows,
-- but expose only the oldest copy in the prompt manager.
WITH duplicates AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id, name, content ORDER BY id) AS position
    FROM system_prompts
    WHERE is_default = TRUE AND deleted_at IS NULL
)
UPDATE system_prompts p
SET deleted_at = NOW(), is_default = FALSE, updated_at = NOW()
FROM duplicates d
WHERE p.id = d.id AND d.position > 1;

-- Different user-authored prompts remain available; only one can be the default.
WITH defaults AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY id) AS position
    FROM system_prompts
    WHERE is_default = TRUE AND deleted_at IS NULL
)
UPDATE system_prompts p
SET is_default = FALSE, updated_at = NOW()
FROM defaults d
WHERE p.id = d.id AND d.position > 1;

CREATE UNIQUE INDEX uk_system_prompts_active_default
    ON system_prompts(user_id) WHERE is_default = TRUE AND deleted_at IS NULL;

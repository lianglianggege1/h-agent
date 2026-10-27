ALTER TABLE generation_tasks ADD COLUMN IF NOT EXISTS result_text TEXT;
ALTER TABLE generation_tasks ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE generation_tasks ALTER COLUMN provider_task_id TYPE VARCHAR(256);
ALTER TABLE generation_tasks ALTER COLUMN provider_file_id TYPE VARCHAR(256);

-- Recover materialization interrupted by an older worker, without submitting a new video.
UPDATE generation_tasks SET next_poll_at = NOW()
WHERE status = 'MATERIALIZING' AND next_poll_at IS NULL;

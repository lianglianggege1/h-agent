-- Preserve the Agent working context independently of unplayed generated speech.
ALTER TABLE voice_turns ADD COLUMN memory_checkpoint TEXT;

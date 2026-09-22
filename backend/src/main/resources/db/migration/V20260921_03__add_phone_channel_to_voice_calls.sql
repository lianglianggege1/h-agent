ALTER TABLE voice_calls ADD COLUMN channel VARCHAR(16) NOT NULL DEFAULT 'BROWSER';
UPDATE voice_calls SET channel = 'BROWSER';
ALTER TABLE voice_calls ALTER COLUMN room_name DROP NOT NULL;
ALTER TABLE voice_calls ALTER COLUMN participant_identity DROP NOT NULL;
ALTER TABLE voice_calls DROP CONSTRAINT voice_calls_room_name_key;
CREATE UNIQUE INDEX voice_browser_room_unique ON voice_calls(room_name) WHERE channel = 'BROWSER' AND room_name IS NOT NULL;
ALTER TABLE voice_turns ADD COLUMN turn_type VARCHAR(16) NOT NULL DEFAULT 'DIALOGUE';
ALTER TABLE voice_turns ALTER COLUMN user_text DROP NOT NULL;

-- Add index for scheduler queue lookup performance
CREATE INDEX IF NOT EXISTS idx_outbound_calls_stage ON outbound_calls(stage) WHERE stage = 'QUEUED';

-- Add index for voice_calls agent lookup
CREATE INDEX IF NOT EXISTS idx_voice_calls_agent ON voice_calls(agent_id) WHERE channel = 'PHONE';

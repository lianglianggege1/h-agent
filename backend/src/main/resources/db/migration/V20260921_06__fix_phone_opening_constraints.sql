-- PHONE/Harness calls do not have an ordinary prompt or a synthetic user message.
ALTER TABLE voice_calls ALTER COLUMN prompt_id DROP NOT NULL;
ALTER TABLE voice_turns ALTER COLUMN user_message_id DROP NOT NULL;
ALTER TABLE agent_runs ALTER COLUMN user_message_id DROP NOT NULL;

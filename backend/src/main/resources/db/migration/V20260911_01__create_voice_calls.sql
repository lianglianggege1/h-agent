CREATE TABLE voice_calls (
    ending_at BIGINT NOT NULL DEFAULT 0,
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(36) NOT NULL,
    prompt_id BIGINT NOT NULL,
    system_prompt TEXT NOT NULL,
    model_name VARCHAR(256) NOT NULL,
    room_name VARCHAR(128) NOT NULL UNIQUE,
    participant_identity VARCHAR(128) NOT NULL,
    dispatch_id VARCHAR(128),
    claim_secret VARCHAR(64) NOT NULL,
    worker_id VARCHAR(128),
    worker_epoch BIGINT NOT NULL DEFAULT 0,
    worker_ready BOOLEAN NOT NULL DEFAULT FALSE,
    participant_joined BOOLEAN NOT NULL DEFAULT FALSE,
    lease_until BIGINT NOT NULL DEFAULT 0,
    disconnected_at BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(32) NOT NULL,
    reason VARCHAR(128),
    context_dirty BOOLEAN NOT NULL DEFAULT FALSE,
    cleanup_pending BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(user_id, request_id)
);
CREATE UNIQUE INDEX voice_one_call_per_user ON voice_calls(user_id)
    WHERE state NOT IN ('ENDED', 'FAILED');
CREATE UNIQUE INDEX voice_one_call_per_session ON voice_calls(session_id)
    WHERE state NOT IN ('ENDED', 'FAILED');
CREATE TABLE voice_turns (
    id VARCHAR(36) PRIMARY KEY,
    call_id VARCHAR(36) NOT NULL REFERENCES voice_calls(id),
    run_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT,
    utterance_id VARCHAR(36) NOT NULL UNIQUE,
    user_text TEXT NOT NULL,
    generated_text TEXT NOT NULL DEFAULT '',
    generation_state VARCHAR(32) NOT NULL DEFAULT 'ACCEPTED',
    playout_state VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    played_chars INTEGER NOT NULL DEFAULT 0 CHECK (played_chars >= 0),
    revision BIGINT NOT NULL DEFAULT 0,
    confidence VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    final_playout BOOLEAN NOT NULL DEFAULT FALSE,
    state VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX voice_one_open_turn ON voice_turns(call_id) WHERE state <> 'COMMITTED';
CREATE INDEX voice_turns_call_order ON voice_turns(call_id, created_at);

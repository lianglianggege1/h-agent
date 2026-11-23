CREATE TABLE automation_proposal_trials (
    id VARCHAR(64) PRIMARY KEY,
    proposal_id VARCHAR(64) NOT NULL REFERENCES automation_proposals(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    proposal_revision TIMESTAMP NOT NULL,
    title VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    output TEXT,
    failure TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (proposal_id, proposal_revision)
);
CREATE INDEX automation_proposal_trials_owner ON automation_proposal_trials (user_id, updated_at DESC);

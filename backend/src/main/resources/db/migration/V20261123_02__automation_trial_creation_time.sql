ALTER TABLE automation_proposal_trials ADD COLUMN created_at TIMESTAMPTZ;

-- Legacy trials only recorded their last update.
UPDATE automation_proposal_trials SET created_at = updated_at;

ALTER TABLE automation_proposal_trials
    ALTER COLUMN created_at SET DEFAULT NOW(),
    ALTER COLUMN created_at SET NOT NULL;

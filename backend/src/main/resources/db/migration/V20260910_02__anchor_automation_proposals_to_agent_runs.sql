-- 提案与生成它的 AgentRun 建立不可变关联，用于聊天时间线原位渲染。
ALTER TABLE automation_proposals
    ADD COLUMN IF NOT EXISTS source_agent_run_id BIGINT;

ALTER TABLE automation_proposals
    DROP CONSTRAINT IF EXISTS fk_automation_proposals_source_run;

ALTER TABLE automation_proposals
    ADD CONSTRAINT fk_automation_proposals_source_run
    FOREIGN KEY (source_agent_run_id)
    REFERENCES agent_runs(id)
    ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_automation_proposals_source_run
    ON automation_proposals(source_agent_run_id);

CREATE INDEX IF NOT EXISTS idx_automation_proposals_session_created
    ON automation_proposals(user_id, source_session_id, created_at, id);

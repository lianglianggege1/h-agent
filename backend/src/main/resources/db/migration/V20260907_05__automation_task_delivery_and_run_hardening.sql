-- 任务携带投递目标快照（V1：SESSION 结果卡片 / NONE 仅管理页可见）。
ALTER TABLE automation_tasks
    ADD COLUMN IF NOT EXISTS delivery_sink VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS delivery_session_id VARCHAR(64);

-- 运行取消与接纳时冻结的 ExecutionSpec 快照。
ALTER TABLE automation_runs
    ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS spec_snapshot TEXT;

-- 重叠运行判定：同一任务只允许一个未终结 Run。
CREATE INDEX IF NOT EXISTS idx_automation_runs_active
    ON automation_runs(task_id)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');

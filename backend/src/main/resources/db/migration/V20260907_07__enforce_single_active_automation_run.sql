-- 将重叠策略下沉到数据库，避免两个调度器同时通过“先查后插”而并行运行同一任务。
CREATE UNIQUE INDEX IF NOT EXISTS uq_automation_runs_single_active
    ON automation_runs(task_id)
    WHERE status IN ('RUNNING', 'CANCEL_REQUESTED');

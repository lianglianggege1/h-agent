-- Run 先进入持久化队列，再由任一应用实例 CAS 领取执行；进程在入队后崩溃不会丢任务。
DROP INDEX IF EXISTS idx_automation_runs_active;
DROP INDEX IF EXISTS uq_automation_runs_single_active;

CREATE INDEX idx_automation_runs_active
    ON automation_runs(task_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE UNIQUE INDEX uq_automation_runs_single_active
    ON automation_runs(task_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX IF NOT EXISTS idx_automation_runs_queued
    ON automation_runs(scheduled_for, started_at)
    WHERE status = 'QUEUED';

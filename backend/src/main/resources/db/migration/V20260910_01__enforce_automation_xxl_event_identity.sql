-- 一个 XXL 执行日志身份只对应一个本地逻辑执行事件。
CREATE UNIQUE INDEX IF NOT EXISTS uq_automation_runs_xxl_trigger
    ON automation_runs(trigger_id)
    WHERE trigger_id LIKE 'xxl:%';

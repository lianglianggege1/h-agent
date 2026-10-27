-- 图片供应商接口同步返回结果；本地队列负责与聊天执行解耦。
CREATE TABLE image_generation_tasks (
    message_id BIGINT PRIMARY KEY REFERENCES chat_session_messages(id) ON DELETE CASCADE,
    command_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);
CREATE INDEX idx_image_generation_tasks_queued ON image_generation_tasks(created_at) WHERE status = 'QUEUED';
CREATE INDEX idx_image_generation_tasks_running ON image_generation_tasks(started_at) WHERE status = 'RUNNING';

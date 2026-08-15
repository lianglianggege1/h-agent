-- 消息和运行都直接归属于统一 Agent Session；session_record_id 只承担顶级页面授权与级联删除。
ALTER TABLE chat_session_messages
    ALTER COLUMN session_id TYPE VARCHAR(255);

ALTER TABLE chat_session_messages
    ADD CONSTRAINT fk_chat_messages_agent_session
    FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id);

DROP INDEX IF EXISTS idx_chat_session_messages_record_seq;
DROP INDEX IF EXISTS idx_chat_session_messages_session_id;

CREATE UNIQUE INDEX uk_chat_session_messages_session_seq
    ON chat_session_messages(session_id, sequence_no);

COMMENT ON COLUMN chat_session_messages.session_record_id IS
    '所属顶级 chat_sessions 记录；授权和级联删除使用';
COMMENT ON COLUMN chat_session_messages.session_id IS '消息实际所属的统一 Agent 会话 ID';
COMMENT ON COLUMN chat_session_messages.sequence_no IS
    '实际 Agent Session 内的消息顺序；父子会话各自独立编号';
COMMENT ON COLUMN chat_sessions.message_count IS
    '根 Agent Session 已持久化的消息数量；不包含子孙会话';
COMMENT ON COLUMN agent_sessions.message_count IS
    '当前实际 Agent Session 已持久化的消息数量，同时作为下一消息序号计数器';

ALTER TABLE agent_runs
    ALTER COLUMN session_id TYPE VARCHAR(255);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_agent_session
    FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id);

COMMENT ON COLUMN agent_runs.session_id IS '本次运行实际所属的统一 Agent 会话 ID';

-- 资源归属由 message_id -> chat_session_messages.session_id 唯一确定，
-- 删除重复的 session_id，避免资源与消息产生两个不一致的会话事实。
DROP INDEX IF EXISTS idx_chat_message_resources_session_id;

ALTER TABLE chat_message_resources
    DROP COLUMN IF EXISTS session_id;

COMMENT ON COLUMN chat_message_resources.message_id IS '资源绑定的产品消息自增主键';

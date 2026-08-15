-- 所有 Agent 类型共用的会话身份与父子拓扑。顶级会话和任意层级协作 Agent 都在这里占一行。
CREATE TABLE IF NOT EXISTS agent_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    parent_session_id VARCHAR(255),
    user_id BIGINT NOT NULL,
    agent_id VARCHAR(128) NOT NULL,
    gateway_subagent_id VARCHAR(128),
    display_order INTEGER,
    message_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_agent_sessions_session_id UNIQUE (session_id),
    CONSTRAINT uk_agent_sessions_gateway_subagent_id UNIQUE (gateway_subagent_id),
    CONSTRAINT fk_agent_sessions_parent FOREIGN KEY (parent_session_id)
        REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_agent_sessions_sibling_order UNIQUE (parent_session_id, display_order),
    CONSTRAINT ck_agent_sessions_not_self_parent CHECK (
        parent_session_id IS NULL OR parent_session_id <> session_id
    ),
    CONSTRAINT ck_agent_sessions_child_shape CHECK (
        (parent_session_id IS NULL AND gateway_subagent_id IS NULL AND display_order IS NULL)
        OR (parent_session_id IS NOT NULL AND display_order IS NOT NULL)
    )
);

COMMENT ON TABLE agent_sessions IS '所有 Agent 的统一会话身份及直接父子拓扑；支持任意层级协作 Agent';
COMMENT ON COLUMN agent_sessions.id IS '数据库内部自增主键';
COMMENT ON COLUMN agent_sessions.session_id IS '全局 Agent 会话 ID；标准、领域、Harness 父子 Agent 共用';
COMMENT ON COLUMN agent_sessions.parent_session_id IS '直接父 Agent 会话 ID；顶级会话为空';
COMMENT ON COLUMN agent_sessions.user_id IS '会话所属用户 ID';
COMMENT ON COLUMN agent_sessions.agent_id IS '当前会话使用的 Agent 类型 ID';
COMMENT ON COLUMN agent_sessions.gateway_subagent_id IS 'Gateway 暴露的可寻址协作 Agent 句柄；非 Gateway 子会话可为空';
COMMENT ON COLUMN agent_sessions.display_order IS '同一个父 Agent 下的稳定展示顺序；顶级会话为空';
COMMENT ON COLUMN agent_sessions.message_count IS '当前 Agent 会话已持久化的产品消息数量';
COMMENT ON COLUMN agent_sessions.created_at IS '会话身份创建时间';
COMMENT ON COLUMN agent_sessions.updated_at IS '会话身份或拓扑最后更新时间';

-- chat_sessions 仍负责顶级聊天页面元数据；其 session_id 必须对应一个根 Agent Session。
ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_agent_session
    FOREIGN KEY (session_id) REFERENCES agent_sessions(session_id);

COMMENT ON COLUMN chat_sessions.session_id IS '顶级聊天页面对应的根 Agent 会话 ID';

-- Harness 专属扩展只保存协作者的产品展示和生命周期状态，父子关系由 agent_sessions 统一维护。
CREATE TABLE IF NOT EXISTS harness_subagents (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    assignment TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    execution_id VARCHAR(255),
    failure_reason VARCHAR(64),
    failure_message TEXT,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_harness_subagents_session_id UNIQUE (session_id),
    CONSTRAINT fk_harness_subagents_session FOREIGN KEY (session_id)
        REFERENCES agent_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT ck_harness_subagent_status CHECK (
        status IN ('AVAILABLE', 'RUNNING', 'COMPLETED', 'FAILED')
    )
);

COMMENT ON TABLE harness_subagents IS 'Harness 协作 Agent 的产品展示信息和当前生命周期状态';
COMMENT ON COLUMN harness_subagents.id IS '数据库内部自增主键';
COMMENT ON COLUMN harness_subagents.session_id IS '协作 Agent 会话 ID，关联统一 agent_sessions';
COMMENT ON COLUMN harness_subagents.display_name IS '面向用户展示的协作者名称';
COMMENT ON COLUMN harness_subagents.assignment IS '父 Agent 委托给协作者的当前任务说明';
COMMENT ON COLUMN harness_subagents.status IS
    '用户可见协作 Agent 状态：AVAILABLE=已暴露可寻址，RUNNING=当前执行中，COMPLETED=最近执行完成，FAILED=最近执行失败';
COMMENT ON COLUMN harness_subagents.execution_id IS
    '最近一轮执行的唯一关联 ID；父流使用 replyId，用户直达子会话使用服务端生成 ID';
COMMENT ON COLUMN harness_subagents.failure_reason IS '最近一轮失败分类；成功或新一轮开始时清空';
COMMENT ON COLUMN harness_subagents.failure_message IS '最近一轮失败详情；仅供服务端诊断';
COMMENT ON COLUMN harness_subagents.started_at IS '最近一轮开始时间';
COMMENT ON COLUMN harness_subagents.finished_at IS '最近一轮进入成功或失败终态的时间';
COMMENT ON COLUMN harness_subagents.created_at IS '协作者首次暴露时间';
COMMENT ON COLUMN harness_subagents.updated_at IS '协作者展示信息或状态最后更新时间';

CREATE INDEX IF NOT EXISTS idx_agent_sessions_parent_order
    ON agent_sessions(parent_session_id, display_order);

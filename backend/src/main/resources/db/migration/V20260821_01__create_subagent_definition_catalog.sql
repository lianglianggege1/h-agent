-- Subagent Definition Catalog：双来源（BUILTIN/USER）可复用 Subagent 定义。
-- 代码库 classpath Markdown 是内置内容的发布真相；数据库保存同步结果、用户定义和 Session 版本引用。

CREATE TABLE IF NOT EXISTS agent_definitions (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    owner_user_id BIGINT,
    agent_id VARCHAR(63) NOT NULL,
    current_published_version INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_agent_definitions_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT ck_agent_definitions_source_shape CHECK (
        (source = 'BUILTIN' AND owner_user_id IS NULL)
        OR (source = 'USER' AND owner_user_id IS NOT NULL)
    ),
    CONSTRAINT ck_agent_definitions_enabled_has_version CHECK (
        enabled = FALSE OR current_published_version IS NOT NULL
    )
);

-- BUILTIN 全局唯一 agent_id；partial index 不限制用户之间使用相同 agent_id。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definitions_builtin_agent_id
    ON agent_definitions(agent_id) WHERE source = 'BUILTIN';

-- 用户定义的 agent_id 在所属账号内唯一；唯一约束包含已删除行，保证软删除后 ID 不被复用。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definitions_user_agent_id
    ON agent_definitions(owner_user_id, agent_id) WHERE source = 'USER';

COMMENT ON TABLE agent_definitions IS 'Subagent 定义身份：BUILTIN 来自代码库同步，USER 属于单个用户';
COMMENT ON COLUMN agent_definitions.source IS '定义来源：BUILTIN / USER';
COMMENT ON COLUMN agent_definitions.owner_user_id IS 'USER 定义所属用户；BUILTIN 为空';
COMMENT ON COLUMN agent_definitions.agent_id IS '稳定逻辑 ID，kebab-case，创建后不可修改';
COMMENT ON COLUMN agent_definitions.current_published_version IS 'USER 定义当前发布版本；BUILTIN 为当前同步版本；无版本时为空';
COMMENT ON COLUMN agent_definitions.enabled IS '是否进入新父 turn 的可用 Catalog';
COMMENT ON COLUMN agent_definitions.deleted_at IS '软删除时间；BUILTIN 不删除';

CREATE TABLE IF NOT EXISTS agent_definition_drafts (
    definition_id BIGINT PRIMARY KEY,
    markdown_content TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    validation_json JSONB,
    updated_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_definition_drafts_definition FOREIGN KEY (definition_id)
        REFERENCES agent_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_definition_drafts_user FOREIGN KEY (updated_by_user_id) REFERENCES users(id)
);

COMMENT ON TABLE agent_definition_drafts IS '用户定义的可变草稿；每个 USER Definition 最多一行，允许校验失败';
COMMENT ON COLUMN agent_definition_drafts.revision IS '乐观并发控制；每次成功保存递增';
COMMENT ON COLUMN agent_definition_drafts.validation_json IS '最近一次保存时的结构化校验结果';

CREATE TABLE IF NOT EXISTS agent_definition_versions (
    id BIGSERIAL PRIMARY KEY,
    definition_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    content_hash CHAR(64) NOT NULL,
    markdown_content TEXT NOT NULL,
    compiled_metadata_json JSONB NOT NULL,
    published_by_user_id BIGINT,
    builtin_release_id VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_agent_definition_versions UNIQUE (definition_id, version),
    CONSTRAINT fk_definition_versions_definition FOREIGN KEY (definition_id)
        REFERENCES agent_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_definition_versions_user FOREIGN KEY (published_by_user_id) REFERENCES users(id),
    CONSTRAINT ck_definition_versions_publisher_shape CHECK (
        (builtin_release_id IS NULL AND published_by_user_id IS NOT NULL)
        OR (builtin_release_id IS NOT NULL AND published_by_user_id IS NULL)
    )
);

-- 内置同步：同一 release 只能登记一个不可变版本；不同 hash 的重复 release 触发启动失败。
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_definition_versions_builtin_release
    ON agent_definition_versions(definition_id, builtin_release_id)
    WHERE builtin_release_id IS NOT NULL;

COMMENT ON TABLE agent_definition_versions IS '不可变发布版本：Markdown 原文、hash 和经平台校验的编译结果';
COMMENT ON COLUMN agent_definition_versions.content_hash IS '规范化 Markdown 的 SHA-256';
COMMENT ON COLUMN agent_definition_versions.compiled_metadata_json IS '平台校验后的执行配置（工具、步骤、工作区模式等）';
COMMENT ON COLUMN agent_definition_versions.published_by_user_id IS 'USER 发布人；BUILTIN 为空';
COMMENT ON COLUMN agent_definition_versions.builtin_release_id IS '内置版本对应的构建/提交身份';

CREATE TABLE IF NOT EXISTS agent_definition_audit_logs (
    id BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT,
    definition_id BIGINT NOT NULL,
    version INTEGER,
    revision BIGINT,
    operation VARCHAR(32) NOT NULL,
    request_id VARCHAR(128),
    metadata_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_definition_audit_definition FOREIGN KEY (definition_id)
        REFERENCES agent_definitions(id) ON DELETE CASCADE,
    CONSTRAINT ck_definition_audit_operation CHECK (
        operation IN ('CREATE_DRAFT', 'SAVE_DRAFT', 'PUBLISH', 'ENABLE', 'DISABLE',
                      'SOFT_DELETE', 'RESTORE', 'BUILTIN_SYNC')
    )
);

COMMENT ON TABLE agent_definition_audit_logs IS '定义管理操作审计；不保存 Markdown 正文';

-- child session 固定 Definition Version；版本身份是重新物化的真相。
ALTER TABLE agent_sessions
    ADD COLUMN IF NOT EXISTS agent_definition_id BIGINT,
    ADD COLUMN IF NOT EXISTS agent_definition_version INTEGER;

ALTER TABLE agent_sessions
    ADD CONSTRAINT fk_agent_sessions_definition_version
    FOREIGN KEY (agent_definition_id, agent_definition_version)
    REFERENCES agent_definition_versions(definition_id, version);

COMMENT ON COLUMN agent_sessions.agent_definition_id IS '协作 Agent Session 固定的 Subagent 定义 ID';
COMMENT ON COLUMN agent_sessions.agent_definition_version IS '协作 Agent Session 固定的定义版本';

CREATE INDEX IF NOT EXISTS idx_agent_definitions_owner
    ON agent_definitions(owner_user_id) WHERE source = 'USER';
CREATE INDEX IF NOT EXISTS idx_agent_definition_versions_definition
    ON agent_definition_versions(definition_id, version);

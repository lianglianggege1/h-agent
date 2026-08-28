-- Skill Git 版本平台：用户个人 Skill 的 Proposal/Release 状态与运行制品登记。
-- 事实归属：Gitee 保存源码历史；MinIO 保存不可变运行制品；本组表只保存身份、状态与 Artifact Descriptor。

CREATE TABLE IF NOT EXISTS skill_definitions (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    skill_key VARCHAR(63) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    description TEXT,
    source_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    active_release_id BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    archived_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_skill_definitions_owner_key UNIQUE (owner_user_id, skill_key),
    CONSTRAINT ck_skill_definitions_source_type CHECK (source_type IN ('USER', 'AGENT')),
    CONSTRAINT fk_skill_definitions_owner FOREIGN KEY (owner_user_id) REFERENCES users(id)
);

COMMENT ON TABLE skill_definitions IS 'User Skill 身份与产品状态；owner 从认证身份推导';
COMMENT ON COLUMN skill_definitions.skill_key IS 'owner 内唯一的 kebab-case key；系统内置 key 为保留名称';
COMMENT ON COLUMN skill_definitions.active_release_id IS '当前生效 Release；不等于启用状态';
COMMENT ON COLUMN skill_definitions.enabled IS '是否允许后续顶层 Agent 请求绑定 Active Release';
COMMENT ON COLUMN skill_definitions.source_type IS '来源：用户创建 USER / Agent 沉淀 AGENT';

CREATE TABLE IF NOT EXISTS skill_releases (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL,
    tag_name VARCHAR(255) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    artifact_store VARCHAR(64) NOT NULL,
    artifact_object_key VARCHAR(512) NOT NULL,
    artifact_object_version_id VARCHAR(128),
    artifact_media_type VARCHAR(128) NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    artifact_size BIGINT NOT NULL,
    builder_version VARCHAR(32) NOT NULL,
    validation_policy_version VARCHAR(32) NOT NULL,
    security_policy_version VARCHAR(32) NOT NULL,
    release_note TEXT NOT NULL,
    manifest_json JSONB NOT NULL,
    validation_summary_json JSONB,
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    revoked_by BIGINT,
    revoked_at TIMESTAMP,
    revoke_reason TEXT,
    CONSTRAINT uk_skill_releases_skill_version UNIQUE (skill_id, version_number),
    CONSTRAINT uk_skill_releases_tag UNIQUE (tag_name),
    CONSTRAINT ck_skill_releases_status CHECK (status IN ('AVAILABLE', 'REVOKED')),
    CONSTRAINT ck_skill_releases_digest CHECK (artifact_digest ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT ck_skill_releases_size CHECK (artifact_size > 0),
    CONSTRAINT ck_skill_releases_media_type CHECK (artifact_media_type = 'application/vnd.h-agent.skill.bundle.v1+tar'),
    CONSTRAINT fk_skill_releases_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_skill_releases_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

COMMENT ON TABLE skill_releases IS '不可变发布版本；Artifact Descriptor 以 PostgreSQL 登记为准，内容身份是 sha256 digest';
COMMENT ON COLUMN skill_releases.version_number IS 'Skill 内独立递增的 vN；不能由用户指定';

CREATE TABLE IF NOT EXISTS skill_proposals (
    id BIGSERIAL PRIMARY KEY,
    skill_id BIGINT NOT NULL,
    base_release_id BIGINT,
    branch_name VARCHAR(255) NOT NULL,
    head_commit_sha VARCHAR(40) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    validation_status VARCHAR(16) NOT NULL DEFAULT 'UNVALIDATED',
    validated_head_sha VARCHAR(40),
    validation_result_json JSONB,
    source_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    source_detail_json JSONB,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_skill_proposals_status CHECK (status IN ('OPEN', 'PUBLISHING')),
    CONSTRAINT ck_skill_proposals_validation_status CHECK (validation_status IN ('UNVALIDATED', 'VALID', 'INVALID')),
    CONSTRAINT ck_skill_proposals_source_type CHECK (source_type IN ('USER', 'AGENT')),
    CONSTRAINT fk_skill_proposals_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id) ON DELETE CASCADE,
    CONSTRAINT fk_skill_proposals_base_release FOREIGN KEY (base_release_id) REFERENCES skill_releases(id)
);

-- 每个 Skill 同时最多一个 OPEN Proposal（设计不变量 5）：部分唯一索引。
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_proposals_open_skill
    ON skill_proposals(skill_id) WHERE status = 'OPEN';

COMMENT ON TABLE skill_proposals IS 'Skill 当前唯一的可变候选内容；发布或放弃后删除记录，不保留草稿历史';

CREATE TABLE IF NOT EXISTS skill_publication_operations (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    skill_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    expected_proposal_head VARCHAR(40) NOT NULL,
    reserved_release_id BIGINT,
    reserved_version_number INTEGER,
    state VARCHAR(32) NOT NULL,
    git_coordinates_json JSONB,
    artifact_descriptor_json JSONB,
    error_code VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_skill_publications_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_skill_publications_state CHECK (
        state IN ('PREPARED', 'GIT_STAGED', 'ARTIFACT_STORED_VERIFIED', 'MASTER_UPDATED',
                  'TAG_VERIFIED', 'RELEASE_INDEXED', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT fk_skill_publications_skill FOREIGN KEY (skill_id) REFERENCES skill_definitions(id)
);

COMMENT ON TABLE skill_publication_operations IS '发布状态机：Git/MinIO/PostgreSQL 跨系统可恢复流程记录';

CREATE TABLE IF NOT EXISTS skill_proposal_write_operations (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    skill_id BIGINT NOT NULL,
    proposal_id BIGINT NOT NULL,
    expected_head_commit_sha VARCHAR(40) NOT NULL,
    target_head_commit_sha VARCHAR(40),
    state VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_skill_proposal_writes_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_skill_proposal_writes_state CHECK (state IN ('OPEN', 'COMPLETED', 'FAILED'))
);

COMMENT ON TABLE skill_proposal_write_operations IS 'Proposal 保存的 Git/数据库部分失败恢复记录；不作为用户可见历史';

CREATE TABLE IF NOT EXISTS skill_operation_logs (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    release_id BIGINT,
    operation VARCHAR(32) NOT NULL,
    from_state_json JSONB,
    to_state_json JSONB,
    actor_user_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_skill_operation_logs_operation CHECK (
        operation IN ('CREATE', 'PUBLISH', 'ACTIVATE', 'ROLLBACK', 'ENABLE', 'DISABLE',
                      'REVOKE', 'ARCHIVE', 'RESTORE', 'DISCARD_PROPOSAL', 'DELETE')
    )
);

COMMENT ON TABLE skill_operation_logs IS 'Skill 管理操作审计；不保存 Skill 正文';

CREATE TABLE IF NOT EXISTS agent_run_skill_bindings (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    skill_key VARCHAR(63) NOT NULL,
    system_revision VARCHAR(64),
    skill_id BIGINT,
    release_id BIGINT,
    artifact_store VARCHAR(64) NOT NULL,
    artifact_object_key VARCHAR(512) NOT NULL,
    artifact_object_version_id VARCHAR(128),
    artifact_media_type VARCHAR(128) NOT NULL,
    artifact_digest VARCHAR(71) NOT NULL,
    artifact_size BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_agent_run_skill_bindings UNIQUE (run_id, skill_key),
    CONSTRAINT ck_agent_run_bindings_source CHECK (source_type IN ('SYSTEM', 'USER'))
);

COMMENT ON TABLE agent_run_skill_bindings IS '一次 Agent 运行实际使用的 Skill Release 与 Artifact Descriptor；不复制正文';

CREATE INDEX IF NOT EXISTS idx_skill_definitions_owner ON skill_definitions(owner_user_id) WHERE archived_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_skill_releases_skill ON skill_releases(skill_id, version_number);
CREATE INDEX IF NOT EXISTS idx_agent_run_skill_bindings_run ON agent_run_skill_bindings(run_id);

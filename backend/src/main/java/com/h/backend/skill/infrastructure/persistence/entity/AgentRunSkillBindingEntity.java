package com.h.backend.skill.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("agent_run_skill_bindings")
public class AgentRunSkillBindingEntity {

    public static final String SOURCE_SYSTEM = "SYSTEM";
    public static final String SOURCE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private Long runId;

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("source_type")
    private String sourceType;

    @TableField("skill_key")
    private String skillKey;

    @TableField("system_revision")
    private String systemRevision;

    @TableField("skill_id")
    private Long skillId;

    @TableField("release_id")
    private Long releaseId;

    @TableField("artifact_store")
    private String artifactStore;

    @TableField("artifact_object_key")
    private String artifactObjectKey;

    @TableField("artifact_object_version_id")
    private String artifactObjectVersionId;

    @TableField("artifact_media_type")
    private String artifactMediaType;

    @TableField("artifact_digest")
    private String artifactDigest;

    @TableField("artifact_size")
    private Long artifactSize;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

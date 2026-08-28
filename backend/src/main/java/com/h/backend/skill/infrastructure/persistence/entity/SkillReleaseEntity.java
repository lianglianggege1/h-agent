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
@TableName("skill_releases")
public class SkillReleaseEntity {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("skill_id")
    private Long skillId;

    @TableField("version_number")
    private Integer versionNumber;

    @TableField("tag_name")
    private String tagName;

    @TableField("commit_sha")
    private String commitSha;

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

    @TableField("builder_version")
    private String builderVersion;

    @TableField("validation_policy_version")
    private String validationPolicyVersion;

    @TableField("security_policy_version")
    private String securityPolicyVersion;

    @TableField("release_note")
    private String releaseNote;

    @TableField("manifest_json")
    private String manifestJson;

    @TableField("validation_summary_json")
    private String validationSummaryJson;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("revoked_by")
    private Long revokedBy;

    @TableField("revoked_at")
    private LocalDateTime revokedAt;

    @TableField("revoke_reason")
    private String revokeReason;
}

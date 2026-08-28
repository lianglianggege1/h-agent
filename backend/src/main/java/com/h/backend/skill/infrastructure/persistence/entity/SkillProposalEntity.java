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
@TableName("skill_proposals")
public class SkillProposalEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PUBLISHING = "PUBLISHING";

    public static final String VALIDATION_UNVALIDATED = "UNVALIDATED";
    public static final String VALIDATION_VALID = "VALID";
    public static final String VALIDATION_INVALID = "INVALID";

    public static final String SOURCE_TYPE_USER = "USER";
    public static final String SOURCE_TYPE_AGENT = "AGENT";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("skill_id")
    private Long skillId;

    @TableField("base_release_id")
    private Long baseReleaseId;

    @TableField("branch_name")
    private String branchName;

    @TableField("head_commit_sha")
    private String headCommitSha;

    @TableField("revision")
    private Long revision;

    @TableField("validation_status")
    private String validationStatus;

    @TableField("validated_head_sha")
    private String validatedHeadSha;

    @TableField("validation_result_json")
    private String validationResultJson;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_detail_json")
    private String sourceDetailJson;

    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

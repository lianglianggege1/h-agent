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
@TableName("skill_publication_operations")
public class SkillPublicationOperationEntity {

    public static final String STATE_PREPARED = "PREPARED";
    public static final String STATE_GIT_STAGED = "GIT_STAGED";
    public static final String STATE_ARTIFACT_STORED_VERIFIED = "ARTIFACT_STORED_VERIFIED";
    public static final String STATE_MASTER_UPDATED = "MASTER_UPDATED";
    public static final String STATE_TAG_VERIFIED = "TAG_VERIFIED";
    public static final String STATE_RELEASE_INDEXED = "RELEASE_INDEXED";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("skill_id")
    private Long skillId;

    @TableField("proposal_id")
    private Long proposalId;

    @TableField("expected_proposal_head")
    private String expectedProposalHead;

    @TableField("reserved_release_id")
    private Long reservedReleaseId;

    @TableField("reserved_version_number")
    private Integer reservedVersionNumber;

    @TableField("state")
    private String state;

    @TableField("git_coordinates_json")
    private String gitCoordinatesJson;

    @TableField("artifact_descriptor_json")
    private String artifactDescriptorJson;

    @TableField("error_code")
    private String errorCode;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

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
@TableName("skill_proposal_write_operations")
public class SkillProposalWriteOperationEntity {

    public static final String STATE_OPEN = "OPEN";
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

    @TableField("expected_head_commit_sha")
    private String expectedHeadCommitSha;

    @TableField("target_head_commit_sha")
    private String targetHeadCommitSha;

    @TableField("state")
    private String state;

    @TableField("error_code")
    private String errorCode;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.h.backend.automation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("automation_proposals")
public class AutomationProposalEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private Long userId;
    @TableField("task_id")
    private String taskId;
    private String action;
    @TableField("payload_json")
    private String payloadJson;
    @TableField("base_task_revision")
    private Long baseTaskRevision;
    private String status;
    @TableField("source_session_id")
    private String sourceSessionId;
    @TableField("created_via")
    private String createdVia;
    @TableField("idempotency_key")
    private String idempotencyKey;
    @TableField("result_task_id")
    private String resultTaskId;
    @TableField("expires_at")
    private LocalDateTime expiresAt;
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;
    @TableField("confirmed_by")
    private Long confirmedBy;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

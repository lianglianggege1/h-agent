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
@TableName("automation_audit_events")
public class AutomationAuditEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    private String action;
    @TableField("target_type")
    private String targetType;
    @TableField("target_id")
    private String targetId;
    @TableField("before_revision")
    private Long beforeRevision;
    @TableField("after_revision")
    private Long afterRevision;
    @TableField("request_key")
    private String requestKey;
    @TableField("detail_json")
    private String detailJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
}

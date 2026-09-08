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
@TableName("automation_deliveries")
public class AutomationDeliveryEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("run_id")
    private String runId;
    @TableField("task_id")
    private String taskId;
    @TableField("user_id")
    private Long userId;
    @TableField("sink_type")
    private String sinkType;
    @TableField("target_json")
    private String targetJson;
    @TableField("payload_json")
    private String payloadJson;
    private String status;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;
    @TableField("lease_owner")
    private String leaseOwner;
    @TableField("lease_until")
    private LocalDateTime leaseUntil;
    @TableField("last_error")
    private String lastError;
    @TableField("delivered_at")
    private LocalDateTime deliveredAt;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

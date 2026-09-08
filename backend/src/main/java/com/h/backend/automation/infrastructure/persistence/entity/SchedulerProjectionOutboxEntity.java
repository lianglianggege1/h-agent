package com.h.backend.automation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("automation_scheduler_outbox")
public class SchedulerProjectionOutboxEntity {
    @TableId
    private String id;
    @TableField("task_id")
    private String taskId;
    @TableField("task_revision")
    private Long taskRevision;
    @TableField("desired_state")
    private String desiredState;
    @TableField("task_name")
    private String taskName;
    @TableField("cron_expression")
    private String cronExpression;
    @TableField("zone_id")
    private String zoneId;
    private String status;
    @TableField("attempt_count")
    private Integer attemptCount;
    @TableField("available_at")
    private LocalDateTime availableAt;
    @TableField("lease_owner")
    private String leaseOwner;
    @TableField("lease_until")
    private LocalDateTime leaseUntil;
    @TableField("last_error")
    private String lastError;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

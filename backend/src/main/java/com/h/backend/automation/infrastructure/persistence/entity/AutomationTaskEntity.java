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
@TableName("automation_tasks")
public class AutomationTaskEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private Long userId;
    private String name;
    private String instruction;
    @TableField("agent_id")
    private String agentId;
    private String runtime;
    @TableField("cron_expression")
    private String cronExpression;
    @TableField("zone_id")
    private String zoneId;
    private Boolean enabled;
    @TableField("next_run_at")
    private LocalDateTime nextRunAt;
    @TableField("last_run_at")
    private LocalDateTime lastRunAt;
    @TableField("last_status")
    private String lastStatus;
    @TableField("created_via")
    private String createdVia;
    private Long revision;
    @TableField("lease_owner")
    private String leaseOwner;
    @TableField("lease_until")
    private LocalDateTime leaseUntil;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}

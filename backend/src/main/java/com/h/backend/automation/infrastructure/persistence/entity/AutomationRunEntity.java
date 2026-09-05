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
@TableName("automation_runs")
public class AutomationRunEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("task_id")
    private String taskId;
    @TableField("user_id")
    private Long userId;
    @TableField("trigger_type")
    private String triggerType;
    private String status;
    @TableField("scheduled_for")
    private LocalDateTime scheduledFor;
    @TableField("started_at")
    private LocalDateTime startedAt;
    @TableField("finished_at")
    private LocalDateTime finishedAt;
    @TableField("session_id")
    private String sessionId;
    private String output;
    @TableField("error_message")
    private String errorMessage;
}

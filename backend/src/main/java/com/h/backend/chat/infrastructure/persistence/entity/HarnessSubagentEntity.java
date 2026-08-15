package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("harness_subagents")
public class HarnessSubagentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 统一 Agent Session ID；父链和 Gateway 句柄都从 agent_sessions 读取。 */
    @TableField("session_id")
    private String sessionId;

    @TableField("display_name")
    private String displayName;

    private String assignment;
    private String status;

    @TableField("execution_id")
    private String executionId;

    @TableField("failure_reason")
    private String failureReason;

    @TableField("failure_message")
    private String failureMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

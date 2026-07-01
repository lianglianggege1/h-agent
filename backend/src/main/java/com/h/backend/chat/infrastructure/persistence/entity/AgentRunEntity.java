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
@TableName("agent_runs")
public class AgentRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("user_message_id")
    private Long userMessageId;

    @TableField("assistant_message_id")
    private Long assistantMessageId;

    private String status;

    @TableField("model_name")
    private String modelName;

    @TableField("langfuse_trace_id")
    private String langfuseTraceId;

    @TableField("tool_count")
    private Integer toolCount;

    @TableField("tool_names_json")
    private String toolNamesJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

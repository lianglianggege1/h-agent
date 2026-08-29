package com.h.backend.memory.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("long_term_memory_capture_outbox")
public class MemoryCaptureOutboxEntity {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_PROCESSING = "PROCESSING";
    public static final String STATE_COMPLETED = "COMPLETED";
    public static final String STATE_RECONCILING = "RECONCILING";
    public static final String STATE_DEAD_LETTER = "DEAD_LETTER";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("operation_key")
    private String operationKey;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("logical_agent_id")
    private String logicalAgentId;

    @TableField("memory_run_id")
    private String memoryRunId;

    @TableField("scope_kind")
    private String scopeKind;

    @TableField("source_execution_id")
    private Long sourceExecutionId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_message_id")
    private Long userMessageId;

    @TableField("assistant_message_id")
    private Long assistantMessageId;

    private String state;

    private Integer attempts;

    @TableField("next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

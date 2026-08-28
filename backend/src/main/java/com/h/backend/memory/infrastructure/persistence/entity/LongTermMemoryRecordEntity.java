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
@TableName("long_term_memory_records")
public class LongTermMemoryRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("remote_memory_id")
    private String remoteMemoryId;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("scope_kind")
    private String scopeKind;

    @TableField("logical_agent_id")
    private String logicalAgentId;

    @TableField("memory_run_id")
    private String memoryRunId;

    private Integer version;

    @TableField("operation_state")
    private String operationState;

    private String source;

    @TableField("source_execution_id")
    private Long sourceExecutionId;

    @TableField("remote_hash")
    private String remoteHash;

    @TableField("remote_updated_at")
    private LocalDateTime remoteUpdatedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}

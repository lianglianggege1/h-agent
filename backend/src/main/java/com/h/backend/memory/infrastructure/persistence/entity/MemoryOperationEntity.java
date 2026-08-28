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
@TableName("long_term_memory_operations")
public class MemoryOperationEntity {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SUCCEEDED = "SUCCEEDED";
    public static final String STATE_FAILED = "FAILED";
    public static final String STATE_RECONCILING = "RECONCILING";
    public static final String STATE_DEAD_LETTER = "DEAD_LETTER";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("remote_memory_id")
    private String remoteMemoryId;

    @TableField("operation_kind")
    private String operationKind;

    @TableField("operation_key")
    private String operationKey;

    private String state;

    @TableField("last_error")
    private String lastError;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.h.backend.chat.infrastructure.persistence.handler.JsonbTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 定义管理操作审计；保存 actor、definition、version、revision 和不含正文的 metadata。
 */
@Getter
@Setter
@TableName(value = "agent_definition_audit_logs", autoResultMap = true)
public class AgentDefinitionAuditLogEntity {

    /** 审计操作类型。 */
    public static final String OP_CREATE_DRAFT = "CREATE_DRAFT";
    public static final String OP_SAVE_DRAFT = "SAVE_DRAFT";
    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_ENABLE = "ENABLE";
    public static final String OP_DISABLE = "DISABLE";
    public static final String OP_SOFT_DELETE = "SOFT_DELETE";
    public static final String OP_RESTORE = "RESTORE";
    public static final String OP_BUILTIN_SYNC = "BUILTIN_SYNC";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("actor_user_id")
    private Long actorUserId;

    @TableField("definition_id")
    private Long definitionId;

    @TableField("version")
    private Integer version;

    @TableField("revision")
    private Long revision;

    @TableField("operation")
    private String operation;

    @TableField("request_id")
    private String requestId;

    /** 不含 Markdown 正文的操作元数据（JSONB，JSON 字符串）。 */
    @TableField(value = "metadata_json", typeHandler = JsonbTypeHandler.class)
    private String metadataJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

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
 * 用户定义的可变草稿；每个 USER Definition 最多一行，允许校验失败。
 *
 * <p>{@code revision} 是乐观并发控制键：保存使用条件更新，过期 revision 返回冲突。</p>
 */
@Getter
@Setter
@TableName(value = "agent_definition_drafts", autoResultMap = true)
public class AgentDefinitionDraftEntity {

    @TableId(type = IdType.INPUT)
    private Long definitionId;

    @TableField("markdown_content")
    private String markdownContent;

    @TableField("revision")
    private Long revision;

    /** 最近一次保存时的结构化校验结果（JSONB，JSON 字符串）。 */
    @TableField(value = "validation_json", typeHandler = JsonbTypeHandler.class)
    private String validationJson;

    @TableField("updated_by_user_id")
    private Long updatedByUserId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

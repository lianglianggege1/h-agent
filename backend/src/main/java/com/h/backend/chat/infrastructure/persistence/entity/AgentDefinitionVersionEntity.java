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
 * 不可变发布版本：Markdown 原文、规范化 hash 和经平台校验的编译结果。
 *
 * <p>USER 版本由用户发布；BUILTIN 版本由代码库 classpath 同步产生，
 * {@code builtinReleaseId} 标识同步时的构建身份。</p>
 */
@Getter
@Setter
@TableName(value = "agent_definition_versions", autoResultMap = true)
public class AgentDefinitionVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("definition_id")
    private Long definitionId;

    @TableField("version")
    private Integer version;

    @TableField("content_hash")
    private String contentHash;

    @TableField("markdown_content")
    private String markdownContent;

    /** 经平台校验后的执行配置（JSONB，JSON 字符串）。 */
    @TableField(value = "compiled_metadata_json", typeHandler = JsonbTypeHandler.class)
    private String compiledMetadataJson;

    @TableField("published_by_user_id")
    private Long publishedByUserId;

    @TableField("builtin_release_id")
    private String builtinReleaseId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

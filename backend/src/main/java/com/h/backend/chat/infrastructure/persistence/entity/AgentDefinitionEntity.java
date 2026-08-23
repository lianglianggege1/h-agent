package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Subagent 定义身份：BUILTIN 来自代码库 classpath 同步，USER 属于单个用户。
 *
 * <p>{@code agentId} 是父模型可见的稳定逻辑 ID；版本身份保存在
 * {@code currentPublishedVersion} 与不可变版本表中，不编码进 agentId。</p>
 */
@Getter
@Setter
@TableName("agent_definitions")
public class AgentDefinitionEntity {

    /** 定义来源。 */
    public static final String SOURCE_BUILTIN = "BUILTIN";
    public static final String SOURCE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("source")
    private String source;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("agent_id")
    private String agentId;

    @TableField("current_published_version")
    private Integer currentPublishedVersion;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

package com.h.backend.skill.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("skill_definitions")
public class SkillDefinitionEntity {

    public static final String SOURCE_TYPE_USER = "USER";
    public static final String SOURCE_TYPE_AGENT = "AGENT";

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("skill_key")
    private String skillKey;

    @TableField("display_name")
    private String displayName;

    @TableField("description")
    private String description;

    @TableField("source_type")
    private String sourceType;

    @TableField("active_release_id")
    private Long activeReleaseId;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("revision")
    private Long revision;

    @TableField("archived_at")
    private LocalDateTime archivedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

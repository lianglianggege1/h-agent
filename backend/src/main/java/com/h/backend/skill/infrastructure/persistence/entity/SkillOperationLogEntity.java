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
@TableName("skill_operation_logs")
public class SkillOperationLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("owner_user_id")
    private Long ownerUserId;

    @TableField("skill_id")
    private Long skillId;

    @TableField("release_id")
    private Long releaseId;

    @TableField("operation")
    private String operation;

    @TableField("from_state_json")
    private String fromStateJson;

    @TableField("to_state_json")
    private String toStateJson;

    @TableField("actor_user_id")
    private Long actorUserId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

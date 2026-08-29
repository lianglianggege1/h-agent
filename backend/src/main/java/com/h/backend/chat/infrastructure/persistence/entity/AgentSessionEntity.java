package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import com.h.backend.chat.domain.approval.ApprovalMode;

import java.time.LocalDateTime;

/**
 * 所有 Agent 类型共用的会话身份与直接父子关系。
 *
 * <p>{@code sessionId} 是运行、消息和并发控制共同使用的稳定身份；
 * {@code parentSessionId} 只指向直接父节点，因此同一结构可表达任意深度。</p>
 */
@Getter
@Setter
@TableName("agent_sessions")
public class AgentSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("parent_session_id")
    private String parentSessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("agent_id")
    private String agentId;

    @TableField("approval_mode")
    private ApprovalMode approvalMode;

    @TableField("gateway_subagent_id")
    private String gatewaySubagentId;

    /** 协作 Agent Session 固定的 Subagent 定义 ID；顶级与历史 Session 为空。 */
    @TableField("agent_definition_id")
    private Long agentDefinitionId;

    /** 协作 Agent Session 固定的定义版本；版本身份是重新物化的真相。 */
    @TableField("agent_definition_version")
    private Integer agentDefinitionVersion;

    @TableField("display_order")
    private Integer displayOrder;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

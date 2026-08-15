package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

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

    @TableField("gateway_subagent_id")
    private String gatewaySubagentId;

    @TableField("display_order")
    private Integer displayOrder;

    @TableField("message_count")
    private Integer messageCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

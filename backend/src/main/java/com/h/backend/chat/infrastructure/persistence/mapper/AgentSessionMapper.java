package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    @Select("""
            SELECT id, session_id, parent_session_id, user_id, agent_id, gateway_subagent_id,
                   display_order, message_count, created_at, updated_at
            FROM agent_sessions
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    AgentSessionEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, session_id, parent_session_id, user_id, agent_id, gateway_subagent_id,
                   display_order, message_count, created_at, updated_at
            FROM agent_sessions
            WHERE parent_session_id = #{parentSessionId}
            ORDER BY display_order ASC
            """)
    List<AgentSessionEntity> selectChildren(@Param("parentSessionId") String parentSessionId);

    /** 原子分配当前实际 Agent Session 内的下一个消息序号。 */
    @Select("""
            UPDATE agent_sessions
            SET message_count = message_count + 1,
                updated_at = NOW()
            WHERE session_id = #{sessionId}
            RETURNING message_count
            """)
    Integer nextMessageSequence(@Param("sessionId") String sessionId);
}

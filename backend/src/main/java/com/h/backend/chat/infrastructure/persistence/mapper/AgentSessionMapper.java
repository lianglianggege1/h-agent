package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    @Select("""
            SELECT id, session_id, parent_session_id, user_id, agent_id, approval_mode, gateway_subagent_id,
                   agent_definition_id, agent_definition_version,
                   display_order, message_count, created_at, updated_at
            FROM agent_sessions
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    AgentSessionEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, session_id, parent_session_id, user_id, agent_id, approval_mode, gateway_subagent_id,
                   agent_definition_id, agent_definition_version,
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

    /** 删除会话树（根 + 全部子孙）关联的 AgentRun；无消息的运行（如语音开场）不随消息级联删除，需在此显式清理。 */
    @Delete("""
            WITH RECURSIVE tree AS (
                SELECT session_id FROM agent_sessions WHERE session_id = #{sessionId}
                UNION ALL
                SELECT s.session_id FROM agent_sessions s JOIN tree t ON s.parent_session_id = t.session_id
            )
            DELETE FROM agent_runs r USING tree t WHERE r.session_id = t.session_id
            """)
    int deleteRunsInSessionTree(@Param("sessionId") String sessionId);
}

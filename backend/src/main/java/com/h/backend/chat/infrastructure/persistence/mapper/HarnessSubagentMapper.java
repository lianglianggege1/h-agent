package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.HarnessSubagentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface HarnessSubagentMapper extends BaseMapper<HarnessSubagentEntity> {

    @Select("""
            SELECT h.id, h.session_id, h.display_name, h.assignment, h.status,
                   h.execution_id, h.failure_reason, h.failure_message, h.started_at, h.finished_at,
                   h.created_at, h.updated_at
            FROM harness_subagents h
            JOIN agent_sessions s ON s.session_id = h.session_id
            WHERE s.parent_session_id = #{parentSessionId}
            ORDER BY s.display_order ASC
            """)
    List<HarnessSubagentEntity> selectByParentSessionId(
            @Param("parentSessionId") String parentSessionId
    );

    /** 返回根节点下完整协作树，调用方通过 parent_session_id 组装任意层级。 */
    @Select("""
            WITH RECURSIVE descendants AS (
                SELECT session_id, parent_session_id, display_order, 1 AS depth
                FROM agent_sessions
                WHERE parent_session_id = #{rootSessionId}
                UNION ALL
                SELECT child.session_id, child.parent_session_id, child.display_order, parent.depth + 1
                FROM agent_sessions child
                JOIN descendants parent ON child.parent_session_id = parent.session_id
            )
            SELECT h.id, h.session_id, h.display_name, h.assignment, h.status,
                   h.execution_id, h.failure_reason, h.failure_message, h.started_at, h.finished_at,
                   h.created_at, h.updated_at
            FROM descendants tree
            JOIN harness_subagents h ON h.session_id = tree.session_id
            ORDER BY tree.depth ASC, tree.parent_session_id ASC, tree.display_order ASC
            """)
    List<HarnessSubagentEntity> selectDescendants(@Param("rootSessionId") String rootSessionId);

    @Select("""
            SELECT id, session_id, display_name, assignment, status,
                   execution_id, failure_reason, failure_message, started_at, finished_at,
                   created_at, updated_at
            FROM harness_subagents
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    HarnessSubagentEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, session_id, display_name, assignment, status,
                   execution_id, failure_reason, failure_message, started_at, finished_at,
                   created_at, updated_at
            FROM harness_subagents
            WHERE session_id = #{sessionId}
            LIMIT 1
            FOR UPDATE
            """)
    HarnessSubagentEntity selectBySessionIdForUpdate(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE harness_subagents
            SET status = 'RUNNING', execution_id = #{executionId},
                failure_reason = NULL, failure_message = NULL,
                started_at = NOW(), finished_at = NULL, updated_at = NOW()
            WHERE session_id = #{sessionId}
              AND status IN ('AVAILABLE', 'COMPLETED', 'FAILED')
            """)
    int startExecution(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId
    );

    @Update("""
            UPDATE harness_subagents
            SET status = 'COMPLETED', failure_reason = NULL, failure_message = NULL,
                finished_at = NOW(), updated_at = NOW()
            WHERE session_id = #{sessionId}
              AND status = 'RUNNING'
              AND execution_id = #{executionId}
            """)
    int completeExecution(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId
    );

    @Update("""
            UPDATE harness_subagents
            SET status = 'FAILED', failure_reason = #{reason}, failure_message = #{message},
                finished_at = NOW(), updated_at = NOW()
            WHERE session_id = #{sessionId}
              AND status = 'RUNNING'
              AND execution_id = #{executionId}
            """)
    int failExecution(
            @Param("sessionId") String sessionId,
            @Param("executionId") String executionId,
            @Param("reason") String reason,
            @Param("message") String message
    );
}

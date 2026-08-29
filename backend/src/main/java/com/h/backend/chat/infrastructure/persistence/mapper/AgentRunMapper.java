package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    @Select("""
            SELECT id, session_id, user_id, prompt_id, user_message_id, assistant_message_id,
                   status, model_name, langfuse_trace_id, approval_mode_snapshot, trace_parent,
                   tool_count, tool_names_json,
                   error_message, started_at, completed_at, created_at, updated_at
            FROM agent_runs
            WHERE session_id = #{sessionId}
            ORDER BY started_at DESC, id DESC
            """)
    List<AgentRunEntity> selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
            FROM agent_runs
            WHERE session_id = #{sessionId} AND status IN ('RUNNING', 'WAITING_APPROVAL')
            """)
    boolean existsOpenRun(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE agent_runs SET status = #{nextStatus}, updated_at = NOW()
            WHERE id = #{runId} AND status = #{expectedStatus}
            """)
    int transitionStatus(@Param("runId") Long runId,
                         @Param("expectedStatus") String expectedStatus,
                         @Param("nextStatus") String nextStatus);

    @Update("""
            UPDATE agent_runs
            SET approval_mode_snapshot = #{approvalMode}, trace_parent = #{traceParent}, updated_at = NOW()
            WHERE id = #{runId}
            """)
    int bindApprovalContext(@Param("runId") Long runId,
                            @Param("approvalMode") String approvalMode,
                            @Param("traceParent") String traceParent);
}

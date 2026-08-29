package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.ApprovalRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequestEntity> {

    String COLUMNS = "approval_id, run_id, user_id, root_session_id, session_id, request_key, "
            + "reply_id, subagent_execution_id, approval_mode, tool_call_ids_json, tool_names_json, "
            + "display_items_json, status, decision, version, requested_at, decided_at, decided_by, updated_at";

    @Insert("""
            INSERT INTO approval_requests (
                approval_id, run_id, user_id, root_session_id, session_id, request_key,
                reply_id, subagent_execution_id, approval_mode,
                tool_call_ids_json, tool_names_json, display_items_json,
                status, version, requested_at, updated_at
            ) VALUES (
                #{approvalId}, #{runId}, #{userId}, #{rootSessionId}, #{sessionId}, #{requestKey},
                #{replyId}, #{subagentExecutionId}, #{approvalMode},
                CAST(#{toolCallIdsJson} AS jsonb), CAST(#{toolNamesJson} AS jsonb),
                CAST(#{displayItemsJson} AS jsonb), #{status}, #{version}, #{requestedAt}, #{updatedAt}
            )
            """)
    int insertApproval(ApprovalRequestEntity entity);

    @Select("SELECT " + COLUMNS + " FROM approval_requests WHERE run_id = #{runId} AND request_key = #{requestKey}")
    ApprovalRequestEntity selectByRunAndRequestKey(@Param("runId") Long runId,
                                                   @Param("requestKey") String requestKey);

    @Select("SELECT " + COLUMNS + " FROM approval_requests "
            + "WHERE user_id = #{userId} AND session_id = #{sessionId} AND status = 'PENDING' "
            + "ORDER BY requested_at DESC LIMIT 1")
    ApprovalRequestEntity selectPendingOwned(@Param("userId") Long userId,
                                             @Param("sessionId") String sessionId);

    @Select("SELECT " + COLUMNS + " FROM approval_requests "
            + "WHERE approval_id = #{approvalId} AND user_id = #{userId}")
    ApprovalRequestEntity selectOwned(@Param("approvalId") String approvalId,
                                      @Param("userId") Long userId);

    @Update("""
            UPDATE approval_requests
            SET status = #{status}, decision = #{decision}, decided_at = NOW(),
                decided_by = #{userId}, version = version + 1, updated_at = NOW()
            WHERE approval_id = #{approvalId} AND user_id = #{userId}
              AND status = 'PENDING' AND version = #{version}
            """)
    int decidePending(@Param("approvalId") String approvalId,
                      @Param("userId") Long userId,
                      @Param("version") int version,
                      @Param("status") String status,
                      @Param("decision") String decision);
}

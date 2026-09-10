package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationProposalEntity;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutomationProposalMapper extends BaseMapper<AutomationProposalEntity> {

    @Select("""
            SELECT * FROM automation_proposals
            WHERE user_id = #{userId} AND id = #{proposalId}
            """)
    AutomationProposalEntity selectOwned(@Param("userId") Long userId, @Param("proposalId") String proposalId);

    @Select("""
            SELECT * FROM automation_proposals
            WHERE user_id = #{userId} AND id = #{proposalId}
            FOR UPDATE
            """)
    AutomationProposalEntity selectOwnedForUpdate(
            @Param("userId") Long userId,
            @Param("proposalId") String proposalId
    );

    @Select("""
            SELECT * FROM automation_proposals
            WHERE user_id = #{userId} AND status = 'PENDING' AND expires_at > #{now}
            ORDER BY created_at DESC
            """)
    List<AutomationProposalEntity> selectPendingOwned(
            @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM automation_proposals
            WHERE user_id = #{userId} AND source_session_id = #{sessionId}
            ORDER BY created_at ASC
            """)
    List<AutomationProposalEntity> selectOwnedBySession(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId
    );

    @Select("""
            SELECT p.id, p.user_id, p.task_id, p.action, p.payload_json, p.base_task_revision,
                   p.status, p.source_session_id, p.created_via, p.idempotency_key,
                   p.result_task_id, p.source_agent_run_id, p.expires_at, p.confirmed_at,
                   p.confirmed_by, p.created_at, p.updated_at,
                   r.status AS run_status,
                   r.user_message_id AS run_user_message_id,
                   r.assistant_message_id AS run_assistant_message_id,
                   r.session_id AS run_session_id,
                   r.user_id AS run_user_id
            FROM automation_proposals p
            LEFT JOIN agent_runs r ON p.source_agent_run_id = r.id
            WHERE p.user_id = #{userId} AND p.source_session_id = #{sessionId}
            ORDER BY p.created_at ASC, p.id ASC
            """)
    List<AnchoredProposalRow> selectAnchoredBySession(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId
    );

    @Select("""
            UPDATE automation_proposals
            SET status = 'CONFIRMED', result_task_id = #{resultTaskId},
                confirmed_at = #{now}, confirmed_by = #{confirmedBy}, updated_at = #{now}
            WHERE id = #{proposalId} AND status = 'PENDING' AND expires_at > #{now}
            RETURNING *
            """)
    AutomationProposalEntity markConfirmed(
            @Param("proposalId") String proposalId,
            @Param("resultTaskId") String resultTaskId,
            @Param("confirmedBy") Long confirmedBy,
            @Param("now") LocalDateTime now
    );

    @Select("""
            UPDATE automation_proposals
            SET status = 'DISCARDED', updated_at = #{now}
            WHERE id = #{proposalId} AND user_id = #{userId} AND status = 'PENDING'
            RETURNING *
            """)
    AutomationProposalEntity markDiscarded(
            @Param("userId") Long userId,
            @Param("proposalId") String proposalId,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_proposals
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE status = 'PENDING' AND expires_at <= #{now}
            """)
    int markExpired(@Param("now") LocalDateTime now);

    /**
     * automation_proposals LEFT JOIN agent_runs 的联查结果。
     * 采用 Lombok getter/setter 而非 record：MyBatis 未开启 argNameBasedConstructorAutoMapping，
     * record 只能依赖易错的构造器位置类型映射；setter + map-underscore-to-camel-case 按列名映射更稳健。
     */
    @Getter
    @Setter
    class AnchoredProposalRow {
        private String id;
        private Long userId;
        private String taskId;
        private String action;
        private String payloadJson;
        private Long baseTaskRevision;
        private String status;
        private String sourceSessionId;
        private String createdVia;
        private String idempotencyKey;
        private String resultTaskId;
        private Long sourceAgentRunId;
        private LocalDateTime expiresAt;
        private LocalDateTime confirmedAt;
        private Long confirmedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String runStatus;
        private Long runUserMessageId;
        private Long runAssistantMessageId;
        private String runSessionId;
        private Long runUserId;
    }
}

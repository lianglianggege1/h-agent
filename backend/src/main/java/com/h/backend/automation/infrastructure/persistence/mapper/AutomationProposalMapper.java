package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationProposalEntity;
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
}

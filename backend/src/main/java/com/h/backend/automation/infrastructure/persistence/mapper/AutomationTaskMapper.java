package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutomationTaskMapper extends BaseMapper<AutomationTaskEntity> {

    @Select("""
            SELECT * FROM automation_tasks
            WHERE user_id = #{userId} AND id = #{taskId} AND deleted_at IS NULL
            """)
    AutomationTaskEntity selectOwned(@Param("userId") Long userId, @Param("taskId") String taskId);

    @Select("""
            SELECT * FROM automation_tasks
            WHERE id = #{taskId} AND deleted_at IS NULL
            """)
    AutomationTaskEntity selectByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT * FROM automation_tasks
            WHERE user_id = #{userId} AND deleted_at IS NULL
            ORDER BY created_at DESC
            """)
    List<AutomationTaskEntity> selectOwnedList(@Param("userId") Long userId);

    @Update("""
            UPDATE automation_tasks SET
                name = #{task.name}, instruction = #{task.instruction}, agent_id = #{task.agentId},
                runtime = #{task.runtime}, cron_expression = #{task.cronExpression}, zone_id = #{task.zoneId},
                enabled = #{task.enabled}, next_run_at = #{task.nextRunAt}, revision = #{task.revision},
                delivery_sink = #{task.deliverySink}, delivery_session_id = #{task.deliverySessionId},
                updated_at = #{task.updatedAt}
            WHERE id = #{taskId} AND user_id = #{userId} AND revision = #{expectedRevision}
              AND deleted_at IS NULL AND (lease_until IS NULL OR lease_until < #{task.updatedAt})
            """)
    int updateOwned(
            @Param("userId") Long userId,
            @Param("taskId") String taskId,
            @Param("expectedRevision") long expectedRevision,
            @Param("task") AutomationTaskEntity task
    );

    @Select("""
            WITH user_lock AS (
                SELECT pg_advisory_xact_lock(#{userId})
            ), enabled_count AS (
                SELECT COUNT(*) AS total
                FROM automation_tasks, user_lock
                WHERE user_id = #{userId} AND enabled = TRUE AND deleted_at IS NULL
            )
            UPDATE automation_tasks
            SET enabled = TRUE, next_run_at = #{nextRunAt}, revision = revision + 1,
                updated_at = #{updatedAt}, lease_owner = NULL, lease_until = NULL
            WHERE id = #{taskId} AND user_id = #{userId} AND revision = #{expectedRevision}
              AND enabled = FALSE AND deleted_at IS NULL
              AND (SELECT total FROM enabled_count) < #{maxEnabled}
            RETURNING *
            """)
    AutomationTaskEntity enableOwned(
            @Param("userId") Long userId,
            @Param("taskId") String taskId,
            @Param("expectedRevision") long expectedRevision,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("updatedAt") LocalDateTime updatedAt,
            @Param("maxEnabled") int maxEnabled
    );

    @Select("""
            UPDATE automation_tasks
            SET enabled = FALSE, next_run_at = NULL, revision = revision + 1,
                updated_at = #{updatedAt}, lease_owner = NULL, lease_until = NULL
            WHERE id = #{taskId} AND user_id = #{userId} AND revision = #{expectedRevision}
              AND enabled = TRUE AND deleted_at IS NULL
            RETURNING *
            """)
    AutomationTaskEntity disableOwned(
            @Param("userId") Long userId,
            @Param("taskId") String taskId,
            @Param("expectedRevision") long expectedRevision,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Select("""
            UPDATE automation_tasks
            SET enabled = FALSE, next_run_at = NULL, lease_owner = NULL, lease_until = NULL,
                deleted_at = #{now}, updated_at = #{now}, revision = revision + 1
            WHERE id = #{taskId} AND user_id = #{userId} AND deleted_at IS NULL
            RETURNING *
            """)
    AutomationTaskEntity softDeleteOwned(
            @Param("userId") Long userId,
            @Param("taskId") String taskId,
            @Param("now") LocalDateTime now
    );

    @Select("""
            WITH due AS (
                SELECT id FROM automation_tasks
                WHERE enabled = TRUE AND deleted_at IS NULL AND next_run_at <= #{now}
                  AND (CAST(#{excludedZoneId} AS VARCHAR) IS NULL OR zone_id <> #{excludedZoneId})
                  AND (lease_until IS NULL OR lease_until < #{now})
                ORDER BY next_run_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE automation_tasks task
            SET lease_owner = #{leaseOwner}, lease_until = #{leaseUntil}, updated_at = #{now}
            FROM due
            WHERE task.id = due.id
            RETURNING task.*
            """)
    List<AutomationTaskEntity> claimDueTasks(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil,
            @Param("excludedZoneId") String excludedZoneId
    );

    @Update("""
            UPDATE automation_tasks
            SET lease_owner = NULL, lease_until = NULL
            WHERE id = #{taskId} AND lease_owner = #{leaseOwner}
            """)
    int releaseLease(@Param("taskId") String taskId, @Param("leaseOwner") String leaseOwner);

    @Update("""
            UPDATE automation_tasks
            SET lease_owner = NULL, lease_until = NULL, next_run_at = #{nextRunAt},
                updated_at = #{now}
            WHERE id = #{taskId} AND lease_owner = #{leaseOwner}
            """)
    int advanceLeaseToNextRun(
            @Param("taskId") String taskId,
            @Param("leaseOwner") String leaseOwner,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_tasks
            SET last_run_at = #{at}, last_status = #{status}, updated_at = #{at}
            WHERE id = #{taskId} AND deleted_at IS NULL
            """)
    int recordRunResult(
            @Param("taskId") String taskId,
            @Param("at") LocalDateTime at,
            @Param("status") String status
    );
}

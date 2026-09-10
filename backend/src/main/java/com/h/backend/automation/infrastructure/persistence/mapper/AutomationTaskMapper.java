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
                session_id = #{task.sessionId},
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

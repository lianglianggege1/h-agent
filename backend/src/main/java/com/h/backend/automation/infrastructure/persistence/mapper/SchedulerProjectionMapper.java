package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.SchedulerProjectionOutboxEntity;
import com.h.backend.automation.application.SchedulerProjectionStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SchedulerProjectionMapper extends BaseMapper<SchedulerProjectionOutboxEntity> {

    @Update("SELECT pg_advisory_xact_lock(hashtextextended(#{taskId}, 0))")
    void lockTask(@Param("taskId") String taskId);

    @Insert("""
            INSERT INTO automation_scheduler_projection(
                task_id, desired_revision, desired_state, sync_status, updated_at
            ) VALUES (
                #{taskId}, #{taskRevision}, #{desiredState}, 'PENDING', #{now}
            )
            ON CONFLICT (task_id) DO UPDATE SET
                desired_revision = EXCLUDED.desired_revision,
                desired_state = EXCLUDED.desired_state,
                sync_status = 'PENDING',
                last_error = NULL,
                updated_at = EXCLUDED.updated_at
            WHERE automation_scheduler_projection.desired_revision <= EXCLUDED.desired_revision
            """)
    int upsertDesired(
            @Param("taskId") String taskId,
            @Param("taskRevision") long taskRevision,
            @Param("desiredState") String desiredState,
            @Param("now") LocalDateTime now
    );

    @Insert("""
            INSERT INTO automation_scheduler_outbox(
                id, task_id, task_revision, desired_state, task_name, cron_expression, zone_id,
                status, attempt_count, available_at, created_at, updated_at
            ) VALUES (
                #{id}, #{taskId}, #{taskRevision}, #{desiredState}, #{taskName}, #{cronExpression}, #{zoneId},
                'PENDING', 0, #{now}, #{now}, #{now}
            )
            ON CONFLICT (task_id, task_revision, desired_state) DO NOTHING
            """)
    int insertOutbox(
            @Param("id") String id,
            @Param("taskId") String taskId,
            @Param("taskRevision") long taskRevision,
            @Param("desiredState") String desiredState,
            @Param("taskName") String taskName,
            @Param("cronExpression") String cronExpression,
            @Param("zoneId") String zoneId,
            @Param("now") LocalDateTime now
    );

    @Select("""
            WITH due AS (
                SELECT id
                FROM automation_scheduler_outbox
                WHERE (status = 'PENDING' AND available_at <= #{now})
                   OR (status = 'PROCESSING' AND lease_until < #{now})
                ORDER BY created_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE automation_scheduler_outbox event
            SET status = 'PROCESSING', lease_owner = #{leaseOwner}, lease_until = #{leaseUntil},
                updated_at = #{now}
            FROM due
            WHERE event.id = due.id
            RETURNING event.*
            """)
    List<SchedulerProjectionOutboxEntity> claim(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit,
            @Param("leaseOwner") String leaseOwner,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Select("""
            SELECT xxl_job_id
            FROM automation_scheduler_projection
            WHERE task_id = #{taskId}
            """)
    Long selectJobId(@Param("taskId") String taskId);

    @Select("""
            SELECT task_id, desired_revision, desired_state, xxl_job_id, synced_revision,
                   sync_status, last_error
            FROM automation_scheduler_projection
            WHERE task_id = #{taskId}
            """)
    SchedulerProjectionStatus selectStatus(@Param("taskId") String taskId);

    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM automation_scheduler_projection
                WHERE task_id = #{taskId} AND desired_revision = #{taskRevision}
                  AND desired_state = #{desiredState}
            )
            """)
    boolean isCurrent(
            @Param("taskId") String taskId,
            @Param("taskRevision") long taskRevision,
            @Param("desiredState") String desiredState
    );

    @Update("""
            UPDATE automation_scheduler_outbox
            SET status = #{status}, lease_owner = NULL, lease_until = NULL,
                last_error = NULL, updated_at = #{now}
            WHERE id = #{outboxId}
            """)
    int finishOutbox(
            @Param("outboxId") String outboxId,
            @Param("status") String status,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_scheduler_projection
            SET xxl_job_id = #{jobId}, synced_revision = #{taskRevision}, sync_status = 'SYNCED',
                last_error = NULL, updated_at = #{now}
            WHERE task_id = #{taskId} AND desired_revision = #{taskRevision}
              AND desired_state = #{desiredState}
            """)
    int markProjectionSynced(
            @Param("taskId") String taskId,
            @Param("taskRevision") long taskRevision,
            @Param("desiredState") String desiredState,
            @Param("jobId") Long jobId,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_scheduler_outbox
            SET status = 'PENDING', attempt_count = attempt_count + 1,
                available_at = #{availableAt}, lease_owner = NULL, lease_until = NULL,
                last_error = #{errorMessage}, updated_at = #{now}
            WHERE id = #{outboxId}
            """)
    int retryOutbox(
            @Param("outboxId") String outboxId,
            @Param("errorMessage") String errorMessage,
            @Param("availableAt") LocalDateTime availableAt,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE automation_scheduler_projection
            SET sync_status = 'SYNC_FAILED', last_error = #{errorMessage}, updated_at = #{now}
            WHERE task_id = #{taskId} AND desired_revision = #{taskRevision}
              AND desired_state = #{desiredState}
            """)
    int markProjectionFailed(
            @Param("taskId") String taskId,
            @Param("taskRevision") long taskRevision,
            @Param("desiredState") String desiredState,
            @Param("errorMessage") String errorMessage,
            @Param("now") LocalDateTime now
    );
}

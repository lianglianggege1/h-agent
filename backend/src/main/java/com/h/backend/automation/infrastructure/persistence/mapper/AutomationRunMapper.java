package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutomationRunMapper extends BaseMapper<AutomationRunEntity> {

    @Insert("""
            INSERT INTO automation_runs(
                id, task_id, user_id, task_revision, trigger_type, trigger_id, status,
                scheduled_for, started_at, finished_at, session_id, output, error_message,
                cancel_requested_at, spec_snapshot
            ) VALUES (
                #{run.id}, #{run.taskId}, #{run.userId}, #{run.taskRevision}, #{run.triggerType},
                #{run.triggerId}, #{run.status}, #{run.scheduledFor}, #{run.startedAt},
                #{run.finishedAt}, #{run.sessionId}, #{run.output}, #{run.errorMessage},
                #{run.cancelRequestedAt}, #{run.specSnapshot}
            )
            ON CONFLICT DO NOTHING
            """)
    int insertScheduledIfAbsent(@Param("run") AutomationRunEntity run);

    @Insert("""
            INSERT INTO automation_runs(
                id, task_id, user_id, task_revision, trigger_type, trigger_id, status,
                scheduled_for, started_at, finished_at, session_id, output, error_message,
                cancel_requested_at, spec_snapshot
            ) VALUES (
                #{run.id}, #{run.taskId}, #{run.userId}, #{run.taskRevision}, #{run.triggerType},
                #{run.triggerId}, #{run.status}, #{run.scheduledFor}, #{run.startedAt},
                #{run.finishedAt}, #{run.sessionId}, #{run.output}, #{run.errorMessage},
                #{run.cancelRequestedAt}, #{run.specSnapshot}
            )
            ON CONFLICT DO NOTHING
            """)
    int insertManualIfNoActive(@Param("run") AutomationRunEntity run);

    /** 终态守卫：只有 RUNNING / CANCEL_REQUESTED 可终结，保证崩溃重放不重复落终态。 */
    @Update("""
            UPDATE automation_runs
            SET status = #{status}, finished_at = #{finishedAt}, session_id = #{sessionId},
                output = #{output}, error_message = #{errorMessage},
                cancel_requested_at = COALESCE(cancel_requested_at, #{cancelRequestedAt})
            WHERE id = #{runId} AND status IN ('RUNNING', 'CANCEL_REQUESTED')
            """)
    int complete(
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("sessionId") String sessionId,
            @Param("output") String output,
            @Param("errorMessage") String errorMessage,
            @Param("cancelRequestedAt") LocalDateTime cancelRequestedAt
    );

    @Select("""
            SELECT * FROM automation_runs
            WHERE user_id = #{userId} AND id = #{runId}
            """)
    AutomationRunEntity selectOwnedRun(@Param("userId") Long userId, @Param("runId") String runId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM automation_runs
                WHERE task_id = #{taskId} AND status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED')
            )
            """)
    boolean existsActiveRun(@Param("taskId") String taskId);

    @Select("""
            UPDATE automation_runs
            SET status = 'RUNNING', started_at = #{startedAt}
            WHERE id = #{runId} AND status = 'QUEUED'
            RETURNING *
            """)
    AutomationRunEntity claimQueuedRun(
            @Param("runId") String runId,
            @Param("startedAt") LocalDateTime startedAt
    );

    @Select("""
            UPDATE automation_runs
            SET trigger_id = #{triggerId}
            WHERE id = #{runId} AND trigger_type = 'MANUAL' AND status = 'QUEUED'
            RETURNING *
            """)
    AutomationRunEntity bindXxlTrigger(
            @Param("runId") String runId,
            @Param("triggerId") String triggerId
    );

    @Select("""
            UPDATE automation_runs
            SET status = CASE WHEN status = 'QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
                cancel_requested_at = #{now},
                finished_at = CASE WHEN status = 'QUEUED' THEN #{now} ELSE finished_at END
            WHERE id = #{runId} AND status IN ('QUEUED', 'RUNNING')
            RETURNING *
            """)
    AutomationRunEntity requestCancel(@Param("runId") String runId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM automation_runs
            WHERE user_id = #{userId} AND task_id = #{taskId}
            ORDER BY started_at DESC
            LIMIT #{limit}
            """)
    List<AutomationRunEntity> selectOwnedRuns(
            @Param("userId") Long userId,
            @Param("taskId") String taskId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT * FROM automation_runs
            WHERE status IN ('RUNNING', 'CANCEL_REQUESTED') AND started_at <= #{before}
            ORDER BY started_at ASC
            LIMIT #{limit}
            """)
    List<AutomationRunEntity> selectActiveStartedBefore(
            @Param("before") LocalDateTime before,
            @Param("limit") int limit
    );
}

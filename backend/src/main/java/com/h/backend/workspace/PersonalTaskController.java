package com.h.backend.workspace;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.generation.application.service.VideoV2Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workspace/tasks")
public class PersonalTaskController {
    private final JdbcClient jdbc;
    private final VideoV2Service videos;

    public PersonalTaskController(DataSource dataSource, VideoV2Service videos) {
        jdbc = JdbcClient.create(dataSource);
        this.videos = videos;
    }

    @GetMapping
    public ApiResponse<List<TaskView>> list(@AuthenticationPrincipal AuthUserPrincipal user,
                                            @RequestParam(defaultValue = "80") int limit) {
        return ApiResponse.ok(jdbc.sql("""
                SELECT kind, id, parent_id, session_id, title, status, changed_at, output, failure, resource_id, can_cancel
                FROM (
                    SELECT 'DOCUMENT' AS kind, d.id, NULL::text AS parent_id, d.session_id,
                           COALESCE(NULLIF(d.state_json::jsonb ->> 'fileName', ''), '文档任务') AS title,
                           d.status, d.updated_at AS changed_at, NULL::text AS output, NULL::text AS failure,
                           NULL::text AS resource_id, FALSE AS can_cancel
                    FROM chat_document_tasks d
                    JOIN agent_sessions s ON s.session_id = d.session_id AND s.user_id = d.user_id
                    WHERE d.user_id = :owner AND d.state_json::jsonb ->> 'actor' IS NULL
                    UNION ALL
                    SELECT 'VIDEO', v.id, NULL::text, v.session_id, '视频生成', v.status,
                           v.updated_at AT TIME ZONE current_setting('TimeZone'), NULL::text, v.failure_message,
                           CASE WHEN v.artifact_mime_type LIKE 'video/%' THEN v.artifact_id END,
                           v.generation_type = 'VIDEO_V2' AND LOWER(COALESCE(v.provider_status, '')) = 'queued'
                             AND v.status = 'IN_PROGRESS'
                    FROM generation_tasks v
                    JOIN agent_sessions s ON s.session_id = v.session_id AND s.user_id = v.user_id
                    WHERE v.user_id = :owner AND v.actor IS NULL
                      AND v.generation_type IN ('VIDEO_V2', 'TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO')
                    UNION ALL
                    SELECT 'AUTOMATION', r.id, r.task_id, COALESCE(r.session_id, t.session_id),
                           t.name, r.status, COALESCE(r.finished_at, r.started_at) AT TIME ZONE current_setting('TimeZone'),
                           LEFT(r.output, 1000), r.error_message, NULL::text, r.status IN ('QUEUED','RUNNING')
                    FROM automation_runs r
                    JOIN automation_tasks t ON t.id = r.task_id AND t.user_id = r.user_id
                    WHERE r.user_id = :owner
                    UNION ALL
                    SELECT 'AUTOMATION', trial.id, NULL::text, trial.session_id, trial.title || '（试跑）',
                           trial.status, trial.updated_at, LEFT(trial.output, 1000), trial.failure, NULL::text, FALSE
                    FROM automation_proposal_trials trial WHERE trial.user_id = :owner
                ) task
                ORDER BY changed_at DESC, kind, id
                LIMIT :limit
                """).param("owner", user.userId()).param("limit", Math.clamp(limit, 1, 150))
                .query((row, index) -> new TaskView(row.getString("kind"), row.getString("id"),
                        row.getString("parent_id"), row.getString("session_id"), row.getString("title"),
                        row.getString("status"), row.getObject("changed_at", OffsetDateTime.class),
                        row.getString("output"), row.getString("failure"), row.getString("resource_id"),
                        row.getBoolean("can_cancel"))).list());
    }

    @PostMapping("/videos/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelVideo(@AuthenticationPrincipal AuthUserPrincipal user,
                                                        @PathVariable String id) {
        boolean owned = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM generation_tasks v
                    JOIN agent_sessions s ON s.session_id=v.session_id AND s.user_id=v.user_id
                    WHERE v.id=? AND v.user_id=? AND v.actor IS NULL AND v.generation_type='VIDEO_V2')
                """).params(id, user.userId()).query(Boolean.class).single();
        if (!owned) throw new BusinessException(40404, "视频任务不存在");
        try {
            return ApiResponse.ok(videos.remove(user.userId(), id, false));
        } catch (IllegalArgumentException unavailable) {
            throw new BusinessException(40901, unavailable.getMessage());
        }
    }

    public record TaskView(String kind, String id, String parentId, String sessionId,
                           String title, String status, OffsetDateTime updatedAt, String output, String failure,
                           String resourceId, boolean canCancel) {}
}

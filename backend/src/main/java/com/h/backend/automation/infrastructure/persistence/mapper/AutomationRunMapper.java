package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutomationRunMapper extends BaseMapper<AutomationRunEntity> {

    @Update("""
            UPDATE automation_runs
            SET status = #{status}, finished_at = #{finishedAt}, session_id = #{sessionId},
                output = #{output}, error_message = #{errorMessage}
            WHERE id = #{runId} AND status = 'RUNNING'
            """)
    int complete(
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("finishedAt") LocalDateTime finishedAt,
            @Param("sessionId") String sessionId,
            @Param("output") String output,
            @Param("errorMessage") String errorMessage
    );

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
}

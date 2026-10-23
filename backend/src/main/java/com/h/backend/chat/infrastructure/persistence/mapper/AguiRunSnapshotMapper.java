package com.h.backend.chat.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AguiRunSnapshotMapper {
    record Snapshot(String runId, String sessionId, Long userId, String requestHash,
                    String status, String snapshotJson, LocalDateTime createdAt) { }

    @Insert("""
            INSERT INTO chat_interaction_runs(run_id, session_id, user_id, request_hash, status, snapshot_json)
            VALUES (#{runId}, #{sessionId}, #{userId}, #{requestHash}, #{status}, #{snapshotJson})
            """)
    void insert(Snapshot snapshot);

    @Update("""
            UPDATE chat_interaction_runs SET status = #{status}, snapshot_json = #{json}, updated_at = NOW()
            WHERE run_id = #{runId} AND user_id = #{userId}
            """)
    void update(@Param("runId") String runId, @Param("userId") Long userId,
                @Param("status") String status, @Param("json") String json);

    @Select("SELECT * FROM chat_interaction_runs WHERE run_id = #{runId} AND user_id = #{userId}")
    Snapshot find(@Param("userId") Long userId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM chat_interaction_runs WHERE session_id = #{sessionId} AND user_id = #{userId}
            ORDER BY created_at ASC
            """)
    List<Snapshot> list(@Param("userId") Long userId, @Param("sessionId") String sessionId);

    @Update("""
            UPDATE chat_interaction_runs SET status = 'CANCEL_REQUESTED'
            WHERE user_id = #{userId} AND session_id = #{sessionId} AND status = 'RUNNING'
              AND (CAST(#{runId} AS VARCHAR) IS NULL OR run_id = #{runId})
            """)
    int requestStop(@Param("userId") Long userId, @Param("sessionId") String sessionId, @Param("runId") String runId);

    @Update("""
            UPDATE chat_interaction_runs SET status = 'INTERRUPTED', updated_at = NOW()
            WHERE run_id = #{runId} AND user_id = #{userId}
              AND status IN ('RUNNING', 'CANCEL_REQUESTED') AND updated_at < NOW() - INTERVAL '60 seconds'
            """)
    int interruptStale(@Param("userId") Long userId, @Param("runId") String runId);
}

package com.h.backend.chat.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ImageGenerationTaskMapper {
    record Task(Long messageId, String commandJson) {}

    @Insert("INSERT INTO image_generation_tasks(message_id, command_json) VALUES (#{messageId}, #{commandJson})")
    void enqueue(@Param("messageId") Long messageId, @Param("commandJson") String commandJson);

    // One atomic claim across application instances; never resubmit RUNNING work to a paid API.
    @Select(value = """
            UPDATE image_generation_tasks SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
            WHERE message_id = (
                SELECT message_id FROM image_generation_tasks WHERE status = 'QUEUED'
                ORDER BY created_at, message_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING message_id AS "messageId", command_json AS "commandJson"
            """, affectData = true)
    Task claim();

    @Select("""
            SELECT message_id AS "messageId", command_json AS "commandJson"
            FROM image_generation_tasks WHERE status = 'RUNNING'
              AND started_at < CURRENT_TIMESTAMP - (#{seconds} * INTERVAL '1 second')
            ORDER BY started_at LIMIT 20
            """)
    List<Task> findStale(@Param("seconds") long seconds);

    @Update("""
            UPDATE image_generation_tasks SET status = #{status}, completed_at = CURRENT_TIMESTAMP
            WHERE message_id = #{messageId} AND status = 'RUNNING'
            """)
    void finish(@Param("messageId") Long messageId, @Param("status") String status);
}

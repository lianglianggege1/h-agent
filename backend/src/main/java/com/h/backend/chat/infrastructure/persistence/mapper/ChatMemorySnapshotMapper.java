package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMemorySnapshotEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMemorySnapshotMapper extends BaseMapper<ChatMemorySnapshotEntity> {

    @Select("""
            SELECT id, session_record_id, session_id, user_id, prompt_id, agent_id, memory_scope, memory_payload_json, memory_format,
                   window_size, source_message_count, snapshot_version, last_compacted_at, created_at, updated_at
            FROM chat_memory_snapshots
            WHERE session_id = #{sessionId}
            ORDER BY updated_at DESC
            LIMIT 1
            """)
    ChatMemorySnapshotEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, session_record_id, session_id, user_id, prompt_id, agent_id, memory_scope, memory_payload_json, memory_format,
                   window_size, source_message_count, snapshot_version, last_compacted_at, created_at, updated_at
            FROM chat_memory_snapshots
            WHERE session_id = #{sessionId}
              AND agent_id = #{agentId}
              AND memory_scope = #{memoryScope}
            LIMIT 1
            """)
    ChatMemorySnapshotEntity selectBySessionScope(
            @Param("sessionId") String sessionId,
            @Param("agentId") String agentId,
            @Param("memoryScope") String memoryScope
    );

    @Select("""
            SELECT id, session_record_id, session_id, user_id, prompt_id, agent_id, memory_scope, memory_payload_json, memory_format,
                   window_size, source_message_count, snapshot_version, last_compacted_at, created_at, updated_at
            FROM chat_memory_snapshots
            WHERE session_id = #{sessionId}
            """)
    List<ChatMemorySnapshotEntity> selectAllBySessionId(@Param("sessionId") String sessionId);

    @Insert("""
            INSERT INTO chat_memory_snapshots(
                session_record_id,
                session_id,
                user_id,
                prompt_id,
                agent_id,
                memory_scope,
                memory_payload_json,
                memory_format,
                window_size,
                source_message_count,
                snapshot_version,
                last_compacted_at,
                created_at,
                updated_at
            )
            VALUES(
                #{entity.sessionRecordId},
                #{entity.sessionId},
                #{entity.userId},
                #{entity.promptId},
                #{entity.agentId},
                #{entity.memoryScope},
                #{entity.memoryPayloadJson},
                #{entity.memoryFormat},
                #{entity.windowSize},
                #{entity.sourceMessageCount},
                #{entity.snapshotVersion},
                #{entity.lastCompactedAt},
                #{entity.createdAt},
                #{entity.updatedAt}
            )
            ON CONFLICT (session_id, agent_id, memory_scope) DO UPDATE
            SET session_record_id = EXCLUDED.session_record_id,
                user_id = EXCLUDED.user_id,
                prompt_id = EXCLUDED.prompt_id,
                agent_id = EXCLUDED.agent_id,
                memory_scope = EXCLUDED.memory_scope,
                memory_payload_json = EXCLUDED.memory_payload_json,
                memory_format = EXCLUDED.memory_format,
                window_size = EXCLUDED.window_size,
                source_message_count = EXCLUDED.source_message_count,
                snapshot_version = EXCLUDED.snapshot_version,
                last_compacted_at = EXCLUDED.last_compacted_at,
                updated_at = EXCLUDED.updated_at
            WHERE chat_memory_snapshots.snapshot_version < EXCLUDED.snapshot_version
            """)
    int upsertLatestSnapshot(@Param("entity") ChatMemorySnapshotEntity entity);

    @Delete("""
            DELETE FROM chat_memory_snapshots
            WHERE session_id = #{sessionId}
            """)
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Delete("""
            DELETE FROM chat_memory_snapshots
            WHERE session_id = #{sessionId}
              AND agent_id = #{agentId}
              AND memory_scope = #{memoryScope}
            """)
    int deleteBySessionScope(
            @Param("sessionId") String sessionId,
            @Param("agentId") String agentId,
            @Param("memoryScope") String memoryScope
    );
}

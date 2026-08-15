package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    @Select("""
            SELECT id, user_id, session_id, prompt_id, agent_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ChatSessionEntity> selectHistoryByUserId(
            @Param("userId") Long userId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT id, user_id, session_id, prompt_id, agent_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
            ORDER BY updated_at DESC, id DESC
            """)
    List<ChatSessionEntity> selectActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id, session_id, prompt_id, agent_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    ChatSessionEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Update("""
            UPDATE chat_sessions
            SET last_active_at = #{now}, updated_at = #{now}
            WHERE id = #{sessionRecordId}
            """)
    int touch(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("now") java.time.LocalDateTime now
    );

    /** 更新根会话摘要，并同步根 Agent Session 已持久化的消息数量。 */
    @Update("""
            UPDATE chat_sessions
            SET last_user_message = #{lastUserMessage},
                title = CASE WHEN title IS NULL OR title = '新会话' THEN #{generatedTitle} ELSE title END,
                message_count = GREATEST(message_count, #{messageCount}),
                last_active_at = #{now},
                updated_at = #{now}
            WHERE id = #{sessionRecordId}
            """)
    int touchAfterUserMessage(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("lastUserMessage") String lastUserMessage,
            @Param("generatedTitle") String generatedTitle,
            @Param("messageCount") int messageCount,
            @Param("now") java.time.LocalDateTime now
    );

    /** 根 Agent 写入非用户消息时，仅同步根消息数量和活跃时间。 */
    @Update("""
            UPDATE chat_sessions
            SET message_count = GREATEST(message_count, #{messageCount}),
                last_active_at = #{now},
                updated_at = #{now}
            WHERE id = #{sessionRecordId}
            """)
    int touchRootMessage(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("messageCount") int messageCount,
            @Param("now") java.time.LocalDateTime now
    );

    @Select("""
            SELECT id, user_id, session_id, prompt_id, agent_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            """)
    List<ChatSessionEntity> selectExpiredActiveSessions(
            @Param("userId") Long userId,
            @Param("cutoff") java.time.LocalDateTime cutoff
    );
}

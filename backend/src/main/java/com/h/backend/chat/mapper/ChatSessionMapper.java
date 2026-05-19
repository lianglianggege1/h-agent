package com.h.backend.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    @Select("""
            SELECT id, user_id, session_id, prompt_id, title, last_user_message, message_count,
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
            SELECT id, user_id, session_id, prompt_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
            ORDER BY updated_at DESC, id DESC
            """)
    List<ChatSessionEntity> selectActiveByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id, session_id, prompt_id, title, last_user_message, message_count,
                   status, last_active_at, created_at, updated_at
            FROM chat_sessions
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    ChatSessionEntity selectBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, user_id, session_id, prompt_id, title, last_user_message, message_count,
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

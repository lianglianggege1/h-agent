package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

@Mapper
public interface ChatSessionMessageMapper extends BaseMapper<ChatSessionMessageEntity> {

    @Select("""
            SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                   content_text, payload_json, created_at
            FROM chat_session_messages
            WHERE session_record_id = #{sessionRecordId}
              AND session_id = #{sessionId}
            ORDER BY sequence_no ASC
            """)
    List<ChatSessionMessageEntity> selectBySessionRecordId(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("sessionId") String sessionId
    );

    @SelectProvider(type = ChatSessionMessageSqlProvider.class, method = "selectPageBySessionRecordId")
    List<ChatSessionMessageEntity> selectPageBySessionRecordId(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit,
            @Param("beforeSeq") Integer beforeSeq
    );

    @SelectProvider(type = ChatSessionMessageSqlProvider.class, method = "selectPageByAgentSessionId")
    List<ChatSessionMessageEntity> selectPageByAgentSessionId(
            @Param("sessionId") String sessionId,
            @Param("limit") int limit,
            @Param("beforeSeq") Integer beforeSeq
    );

    @Select("""
            SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                   content_text, payload_json, created_at
            FROM chat_session_messages
            WHERE session_record_id = #{sessionRecordId}
              AND session_id = #{sessionId}
            ORDER BY sequence_no DESC
            LIMIT #{limit}
            """)
    List<ChatSessionMessageEntity> selectLatestBySessionRecordId(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("sessionId") String sessionId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM chat_session_messages
                WHERE session_id = #{sessionId}
                  AND role_code = 'assistant'
            )
            """)
    boolean existsAssistantMessage(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id
            FROM chat_session_messages
            WHERE session_id = #{sessionId}
              AND role_code = 'assistant'
            ORDER BY sequence_no DESC
            LIMIT 1
            """)
    Long selectLatestAssistantMessageId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                   content_text, payload_json, created_at
            FROM chat_session_messages
            WHERE session_id = #{sessionId}
            ORDER BY sequence_no DESC
            LIMIT 1
            """)
    ChatSessionMessageEntity selectLatestByAgentSessionId(@Param("sessionId") String sessionId);

    class ChatSessionMessageSqlProvider {

        public String selectPageBySessionRecordId(@Param("beforeSeq") Integer beforeSeq) {
            StringBuilder sql = new StringBuilder("""
                    SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                           content_text, payload_json, created_at
                    FROM chat_session_messages
                    WHERE session_record_id = #{sessionRecordId}
                      AND session_id = #{sessionId}
                    """);
            appendCursor(sql, beforeSeq);
            return sql.toString();
        }

        public String selectPageByAgentSessionId(@Param("beforeSeq") Integer beforeSeq) {
            StringBuilder sql = new StringBuilder("""
                    SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                           content_text, payload_json, created_at
                    FROM chat_session_messages
                    WHERE session_id = #{sessionId}
                    """);
            appendCursor(sql, beforeSeq);
            return sql.toString();
        }

        private void appendCursor(StringBuilder sql, Integer beforeSeq) {
            if (beforeSeq != null) {
                sql.append("\n  AND sequence_no < #{beforeSeq}");
            }
            sql.append("""

                    ORDER BY sequence_no DESC
                    LIMIT #{limit}
                    """);
        }
    }
}

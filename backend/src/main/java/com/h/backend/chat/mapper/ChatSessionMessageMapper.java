package com.h.backend.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.entity.ChatSessionMessageEntity;
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
            ORDER BY sequence_no ASC
            """)
    List<ChatSessionMessageEntity> selectBySessionRecordId(@Param("sessionRecordId") Long sessionRecordId);

    @SelectProvider(type = ChatSessionMessageSqlProvider.class, method = "selectPageBySessionRecordId")
    List<ChatSessionMessageEntity> selectPageBySessionRecordId(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("limit") int limit,
            @Param("beforeSeq") Integer beforeSeq
    );

    @Select("""
            SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                   content_text, payload_json, created_at
            FROM chat_session_messages
            WHERE session_record_id = #{sessionRecordId}
            ORDER BY sequence_no DESC
            LIMIT #{limit}
            """)
    List<ChatSessionMessageEntity> selectLatestBySessionRecordId(
            @Param("sessionRecordId") Long sessionRecordId,
            @Param("limit") int limit
    );

    class ChatSessionMessageSqlProvider {

        public String selectPageBySessionRecordId(@Param("beforeSeq") Integer beforeSeq) {
            StringBuilder sql = new StringBuilder("""
                    SELECT id, session_record_id, session_id, user_id, sequence_no, message_type, role_code,
                           content_text, payload_json, created_at
                    FROM chat_session_messages
                    WHERE session_record_id = #{sessionRecordId}
                    """);
            if (beforeSeq != null) {
                sql.append("\n  AND sequence_no < #{beforeSeq}");
            }
            sql.append("""

                    ORDER BY sequence_no DESC
                    LIMIT #{limit}
                    """);
            return sql.toString();
        }
    }
}

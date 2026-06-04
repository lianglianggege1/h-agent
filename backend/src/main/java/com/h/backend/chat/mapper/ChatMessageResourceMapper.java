package com.h.backend.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.entity.ChatMessageResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageResourceMapper extends BaseMapper<ChatMessageResourceEntity> {

    @Select("""
            <script>
            SELECT id, message_id, user_id, session_id, resource_kind, storage_type, storage_key,
                   view_url, download_url, mime_type, file_name, file_size, width, height, sha256, created_at
            FROM chat_message_resources
            WHERE message_id IN
            <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
            </foreach>
            ORDER BY created_at ASC
            </script>
            """)
    List<ChatMessageResourceEntity> selectByMessageIds(@Param("messageIds") List<Long> messageIds);

    @Select("""
            SELECT id, message_id, user_id, session_id, resource_kind, storage_type, storage_key,
                   view_url, download_url, mime_type, file_name, file_size, width, height, sha256, created_at
            FROM chat_message_resources
            WHERE id = #{id}
            """)
    ChatMessageResourceEntity selectByResourceId(@Param("id") String id);
}

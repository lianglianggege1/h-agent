package com.h.backend.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    @Select("""
            SELECT id, user_id, prompt_id, file_name, source_type, file_type, file_size,
                   char_count, segment_count, status, error_msg, content_hash,
                   created_at, updated_at
            FROM knowledge_document
            WHERE user_id = #{userId} AND prompt_id = #{promptId}
            ORDER BY created_at DESC, id DESC
            """)
    List<KnowledgeDocumentEntity> selectByUserAndPrompt(@Param("userId") Long userId,
                                                        @Param("promptId") Long promptId);
}

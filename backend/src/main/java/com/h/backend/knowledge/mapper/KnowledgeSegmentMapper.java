package com.h.backend.knowledge.mapper;

import com.h.backend.knowledge.dto.SegmentDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeSegmentMapper {

    @Select("""
            SELECT text AS text, metadata::text AS metadata
            FROM knowledge_embeddings
            WHERE metadata->>'docId' = #{docId}
            ORDER BY embedding_id
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SegmentDto> selectByDocId(@Param("docId") String docId,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);
}

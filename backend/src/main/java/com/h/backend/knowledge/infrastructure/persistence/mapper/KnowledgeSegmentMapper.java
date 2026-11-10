package com.h.backend.knowledge.infrastructure.persistence.mapper;

import com.h.backend.knowledge.interfaces.dto.SegmentDto;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface KnowledgeSegmentMapper {
    @Select("""
        SELECT e.text AS text, e.metadata::text AS metadata
        FROM knowledge_embeddings e JOIN knowledge_document d ON d.id::text=e.metadata->>'docId'
        WHERE d.id::text=#{docId} AND d.status='COMPLETED'
          AND e.metadata->>'userId'=d.user_id::text
          AND COALESCE(e.metadata->>'revision','1')=d.version::text
        ORDER BY CASE WHEN e.metadata->>'index' ~ '^[0-9]{1,9}$' THEN (e.metadata->>'index')::integer END NULLS LAST,
                 e.embedding_id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<SegmentDto> selectByDocId(@Param("docId") String docId,@Param("limit") int limit,@Param("offset") int offset);
}

package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionDraftEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentDefinitionDraftMapper extends BaseMapper<AgentDefinitionDraftEntity> {

    @Select("""
            SELECT definition_id, markdown_content, revision, validation_json,
                   updated_by_user_id, created_at, updated_at
            FROM agent_definition_drafts
            WHERE definition_id = #{definitionId}
            LIMIT 1
            """)
    AgentDefinitionDraftEntity selectByDefinitionId(@Param("definitionId") long definitionId);

    @Select("""
            SELECT definition_id, markdown_content, revision, validation_json,
                   updated_by_user_id, created_at, updated_at
            FROM agent_definition_drafts
            WHERE definition_id = #{definitionId}
            FOR UPDATE
            """)
    AgentDefinitionDraftEntity selectByDefinitionIdForUpdate(@Param("definitionId") long definitionId);

    /** 当前用户全部草稿（含已删除定义）；管理列表一次取回避免 N+1。 */
    @Select("""
            SELECT dr.definition_id, dr.markdown_content, dr.revision, dr.validation_json,
                   dr.updated_by_user_id, dr.created_at, dr.updated_at
            FROM agent_definition_drafts dr
            JOIN agent_definitions d ON d.id = dr.definition_id
            WHERE d.source = 'USER' AND d.owner_user_id = #{userId}
            """)
    List<AgentDefinitionDraftEntity> selectDraftsOwnedByUser(@Param("userId") long userId);

    /**
     * 乐观并发保存：仅当 revision 与期望一致时更新，未命中即 revision conflict。
     * 返回受影响行数。
     */
    @Update("""
            UPDATE agent_definition_drafts
            SET markdown_content = #{markdownContent},
                revision = revision + 1,
                validation_json = CAST(#{validationJson} AS jsonb),
                updated_by_user_id = #{userId},
                updated_at = NOW()
            WHERE definition_id = #{definitionId} AND revision = #{expectedRevision}
            """)
    int updateWithRevision(
            @Param("definitionId") long definitionId,
            @Param("expectedRevision") long expectedRevision,
            @Param("markdownContent") String markdownContent,
            @Param("validationJson") String validationJson,
            @Param("userId") long userId);
}

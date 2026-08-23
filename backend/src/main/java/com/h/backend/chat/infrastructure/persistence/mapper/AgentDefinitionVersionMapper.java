package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionVersionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentDefinitionVersionMapper extends BaseMapper<AgentDefinitionVersionEntity> {

    @Select("""
            SELECT id, definition_id, version, content_hash, markdown_content, compiled_metadata_json,
                   published_by_user_id, builtin_release_id, created_at
            FROM agent_definition_versions
            WHERE definition_id = #{definitionId} AND version = #{version}
            LIMIT 1
            """)
    AgentDefinitionVersionEntity selectByDefinitionAndVersion(
            @Param("definitionId") long definitionId, @Param("version") int version);

    @Select("""
            SELECT id, definition_id, version, content_hash, markdown_content, compiled_metadata_json,
                   published_by_user_id, builtin_release_id, created_at
            FROM agent_definition_versions
            WHERE definition_id = #{definitionId}
            ORDER BY version DESC
            """)
    List<AgentDefinitionVersionEntity> selectByDefinitionId(@Param("definitionId") long definitionId);

    /** 行级锁读取；发布事务使用。 */
    @Select("""
            SELECT id, definition_id, version, content_hash, markdown_content, compiled_metadata_json,
                   published_by_user_id, builtin_release_id, created_at
            FROM agent_definition_versions
            WHERE definition_id = #{definitionId} AND version = #{version}
            FOR UPDATE
            """)
    AgentDefinitionVersionEntity selectByDefinitionAndVersionForUpdate(
            @Param("definitionId") long definitionId, @Param("version") int version);

    /** 内置同步：按 release 查找已登记版本，同一 release 多节点复用同一行。 */
    @Select("""
            SELECT id, definition_id, version, content_hash, markdown_content, compiled_metadata_json,
                   published_by_user_id, builtin_release_id, created_at
            FROM agent_definition_versions
            WHERE definition_id = #{definitionId} AND builtin_release_id = #{releaseId}
            LIMIT 1
            """)
    AgentDefinitionVersionEntity selectBuiltinByRelease(
            @Param("definitionId") long definitionId, @Param("releaseId") String releaseId);

    /** 当前最大版本号；发布与内置同步分配下一 version。 */
    @Select("""
            SELECT COALESCE(MAX(version), 0)
            FROM agent_definition_versions
            WHERE definition_id = #{definitionId}
            """)
    int selectMaxVersion(@Param("definitionId") long definitionId);

    /** 显式插入不可变版本行；compiled_metadata_json 以 jsonb 写入。 */
    @Insert("""
            INSERT INTO agent_definition_versions
                (definition_id, version, content_hash, markdown_content, compiled_metadata_json,
                 published_by_user_id, builtin_release_id, created_at)
            VALUES
                (#{definitionId}, #{version}, #{contentHash}, #{markdownContent},
                 CAST(#{compiledMetadataJson} AS jsonb), #{publishedByUserId}, #{builtinReleaseId}, NOW())
            """)
    int insertVersion(
            @Param("definitionId") long definitionId,
            @Param("version") int version,
            @Param("contentHash") String contentHash,
            @Param("markdownContent") String markdownContent,
            @Param("compiledMetadataJson") String compiledMetadataJson,
            @Param("publishedByUserId") Long publishedByUserId,
            @Param("builtinReleaseId") String builtinReleaseId);
}

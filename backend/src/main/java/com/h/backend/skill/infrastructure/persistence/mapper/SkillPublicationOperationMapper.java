package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillPublicationOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillPublicationOperationMapper extends BaseMapper<SkillPublicationOperationEntity> {

    @Select("""
            SELECT * FROM skill_publication_operations
            WHERE idempotency_key = #{key} LIMIT 1
            """)
    SkillPublicationOperationEntity selectByIdempotencyKey(@Param("key") String key);

    @Select("""
            SELECT * FROM skill_publication_operations WHERE id = #{id} LIMIT 1
            """)
    SkillPublicationOperationEntity selectById(@Param("id") long id);

    @Update("""
            UPDATE skill_publication_operations
            SET state = #{state}, git_coordinates_json = #{gitCoordinates}::jsonb,
                artifact_descriptor_json = #{artifactDescriptor}::jsonb,
                error_code = #{errorCode}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateState(@Param("id") long id, @Param("state") String state,
                    @Param("gitCoordinates") String gitCoordinates,
                    @Param("artifactDescriptor") String artifactDescriptor,
                    @Param("errorCode") String errorCode);

    /** 登记预留的 Release 身份，使同 Idempotency-Key 重试可定位既有 Release。 */
    @Update("""
            UPDATE skill_publication_operations
            SET reserved_release_id = #{releaseId}, reserved_version_number = #{versionNumber}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateReservedRelease(@Param("id") long id, @Param("releaseId") long releaseId,
                              @Param("versionNumber") int versionNumber);
}

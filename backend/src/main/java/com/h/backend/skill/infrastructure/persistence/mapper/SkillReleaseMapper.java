package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkillReleaseMapper extends BaseMapper<SkillReleaseEntity> {

    /** JSONB 列不能用 BaseMapper 的隐式 insert（参数按 varchar 发送会被 PostgreSQL 拒绝），必须显式 cast。 */
    @Insert("""
            INSERT INTO skill_releases (skill_id, version_number, tag_name, commit_sha,
                    artifact_store, artifact_object_key, artifact_object_version_id, artifact_media_type,
                    artifact_digest, artifact_size, builder_version, validation_policy_version,
                    security_policy_version, release_note, manifest_json, validation_summary_json,
                    status, created_by)
            VALUES (#{skillId}, #{versionNumber}, #{tagName}, #{commitSha},
                    #{artifactStore}, #{artifactObjectKey}, #{artifactObjectVersionId}, #{artifactMediaType},
                    #{artifactDigest}, #{artifactSize}, #{builderVersion}, #{validationPolicyVersion},
                    #{securityPolicyVersion}, #{releaseNote}, #{manifestJson}::jsonb, #{validationSummaryJson}::jsonb,
                    #{status}, #{createdBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRelease(SkillReleaseEntity entity);

    @Select("""
            SELECT * FROM skill_releases
            WHERE id = #{id} AND skill_id = #{skillId}
            """)
    SkillReleaseEntity selectBySkillAndId(@Param("skillId") long skillId, @Param("id") long id);

    @Select("""
            SELECT * FROM skill_releases
            WHERE skill_id = #{skillId}
            ORDER BY version_number DESC
            """)
    List<SkillReleaseEntity> selectBySkillId(@Param("skillId") long skillId);

    @Select("""
            SELECT COALESCE(MAX(version_number), 0) FROM skill_releases
            WHERE skill_id = #{skillId}
            """)
    int selectMaxVersion(@Param("skillId") long skillId);

    /** 快照选择：状态 AVAILABLE 的 Release（行级读取已由定义行锁覆盖）。 */
    @Select("""
            SELECT * FROM skill_releases
            WHERE skill_id = #{skillId} AND status = 'AVAILABLE'
            ORDER BY version_number DESC
            """)
    List<SkillReleaseEntity> selectAvailableBySkillId(@Param("skillId") long skillId);

    /** 撤销：仅 AVAILABLE 可撤销；Enabled 且为 Active 时由服务层前置校验。 */
    @Update("""
            UPDATE skill_releases
            SET status = 'REVOKED', revoked_by = #{actorUserId}, revoked_at = NOW(), revoke_reason = #{reason}
            WHERE id = #{id} AND skill_id = #{skillId} AND status = 'AVAILABLE'
            """)
    int revokeRelease(@Param("skillId") long skillId, @Param("id") long id,
                      @Param("actorUserId") long actorUserId, @Param("reason") String reason);

    @Select("""
            SELECT COUNT(*) FROM skill_releases WHERE skill_id = #{skillId}
            """)
    long countBySkillId(@Param("skillId") long skillId);
}

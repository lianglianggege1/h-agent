package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkillReleaseMapper extends BaseMapper<SkillReleaseEntity> {

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

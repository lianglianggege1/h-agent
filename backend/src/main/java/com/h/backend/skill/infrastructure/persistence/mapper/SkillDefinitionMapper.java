package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SkillDefinitionMapper extends BaseMapper<SkillDefinitionEntity> {

    /** 用户未归档 Skill 列表。 */
    @Select("""
            SELECT * FROM skill_definitions
            WHERE owner_user_id = #{userId} AND archived_at IS NULL
            ORDER BY updated_at DESC
            """)
    List<SkillDefinitionEntity> selectOwnedActive(@Param("userId") long userId);

    @Select("""
            SELECT * FROM skill_definitions
            WHERE id = #{id} AND owner_user_id = #{userId}
            """)
    SkillDefinitionEntity selectOwnedById(@Param("id") long id, @Param("userId") long userId);

    /** 行级锁读取；发布/生效/启停/归档事务使用。 */
    @Select("""
            SELECT * FROM skill_definitions
            WHERE id = #{id} AND owner_user_id = #{userId}
            FOR UPDATE
            """)
    SkillDefinitionEntity selectOwnedByIdForUpdate(@Param("id") long id, @Param("userId") long userId);

    /** 未归档 Skill 数（配额）。 */
    @Select("""
            SELECT COUNT(*) FROM skill_definitions
            WHERE owner_user_id = #{userId} AND archived_at IS NULL
            """)
    long countActiveOwned(@Param("userId") long userId);

    /** 期望 revision 的乐观锁指针更新；返回 0 表示并发冲突。 */
    @Update("""
            UPDATE skill_definitions
            SET active_release_id = #{releaseId}, revision = revision + 1, updated_at = NOW()
            WHERE id = #{id} AND owner_user_id = #{userId} AND revision = #{expectedRevision}
            """)
    int casActivateRelease(@Param("id") long id, @Param("userId") long userId,
                            @Param("releaseId") Long releaseId, @Param("expectedRevision") long expectedRevision);

    @Update("""
            UPDATE skill_definitions
            SET enabled = #{enabled}, revision = revision + 1, updated_at = NOW()
            WHERE id = #{id} AND owner_user_id = #{userId} AND revision = #{expectedRevision}
            """)
    int casSetEnabled(@Param("id") long id, @Param("userId") long userId,
                      @Param("enabled") boolean enabled, @Param("expectedRevision") long expectedRevision);

    /** 归档自动停用（设计不变量 20）。 */
    @Update("""
            UPDATE skill_definitions
            SET archived_at = NOW(), enabled = FALSE, revision = revision + 1, updated_at = NOW()
            WHERE id = #{id} AND owner_user_id = #{userId} AND archived_at IS NULL AND revision = #{expectedRevision}
            """)
    int casArchive(@Param("id") long id, @Param("userId") long userId,
                   @Param("expectedRevision") long expectedRevision);

    /** 恢复归档只恢复可见性，不自动启用。 */
    @Update("""
            UPDATE skill_definitions
            SET archived_at = NULL, revision = revision + 1, updated_at = NOW()
            WHERE id = #{id} AND owner_user_id = #{userId} AND archived_at IS NOT NULL AND revision = #{expectedRevision}
            """)
    int casRestore(@Param("id") long id, @Param("userId") long userId,
                   @Param("expectedRevision") long expectedRevision);

    /** 快照选择：未归档、已启用、存在生效 Release。 */
    @Select("""
            SELECT * FROM skill_definitions
            WHERE owner_user_id = #{userId} AND archived_at IS NULL AND enabled = TRUE
              AND active_release_id IS NOT NULL
            ORDER BY skill_key ASC
            """)
    List<SkillDefinitionEntity> selectSnapshotCandidates(@Param("userId") long userId);
}

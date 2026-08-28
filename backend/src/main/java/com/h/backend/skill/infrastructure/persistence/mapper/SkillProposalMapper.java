package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillProposalEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillProposalMapper extends BaseMapper<SkillProposalEntity> {

    @Select("""
            SELECT * FROM skill_proposals
            WHERE skill_id = #{skillId} AND status = 'OPEN'
            LIMIT 1
            """)
    SkillProposalEntity selectOpenBySkillId(@Param("skillId") long skillId);

    @Select("""
            SELECT * FROM skill_proposals WHERE id = #{id} LIMIT 1
            """)
    SkillProposalEntity selectById(@Param("id") long id);

    /** expected head CAS：Git push 成功后才推进数据库 head。 */
    @Update("""
            UPDATE skill_proposals
            SET head_commit_sha = #{newHead}, revision = revision + 1, updated_at = NOW(),
                updated_by = #{updatedBy}, validation_status = 'UNVALIDATED', validated_head_sha = NULL
            WHERE id = #{id} AND status = 'OPEN' AND head_commit_sha = #{expectedHead}
            """)
    int advanceHead(@Param("id") long id, @Param("expectedHead") String expectedHead,
                    @Param("newHead") String newHead, @Param("updatedBy") long updatedBy);

    @Update("""
            UPDATE skill_proposals
            SET validation_status = #{status}, validated_head_sha = #{validatedHead},
                validation_result_json = #{resultJson}::jsonb, updated_at = NOW()
            WHERE id = #{id} AND head_commit_sha = #{validatedHead} AND status = 'OPEN'
            """)
    int recordValidation(@Param("id") long id, @Param("validatedHead") String validatedHead,
                         @Param("status") String status, @Param("resultJson") String resultJson);

    @Update("""
            UPDATE skill_proposals
            SET status = 'PUBLISHING', updated_at = NOW()
            WHERE id = #{id} AND status = 'OPEN' AND head_commit_sha = #{head}
            """)
    int markPublishing(@Param("id") long id, @Param("head") String head);

    @Update("""
            UPDATE skill_proposals
            SET status = 'OPEN', updated_at = NOW()
            WHERE id = #{id} AND status = 'PUBLISHING'
            """)
    int reopen(@Param("id") long id);
}

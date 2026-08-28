package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillProposalWriteOperationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SkillProposalWriteOperationMapper extends BaseMapper<SkillProposalWriteOperationEntity> {

    @Select("""
            SELECT * FROM skill_proposal_write_operations
            WHERE idempotency_key = #{key} LIMIT 1
            """)
    SkillProposalWriteOperationEntity selectByIdempotencyKey(@Param("key") String key);

    @Update("""
            UPDATE skill_proposal_write_operations
            SET state = #{state}, target_head_commit_sha = #{targetHead},
                error_code = #{errorCode}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateState(@Param("id") long id, @Param("state") String state,
                    @Param("targetHead") String targetHead, @Param("errorCode") String errorCode);
}

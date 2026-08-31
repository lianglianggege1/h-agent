package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillOperationLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillOperationLogMapper extends BaseMapper<SkillOperationLogEntity> {

    /** JSONB 列不能用 BaseMapper 的隐式 insert（参数按 varchar 发送会被 PostgreSQL 拒绝），必须显式 cast。 */
    @Insert("""
            INSERT INTO skill_operation_logs (owner_user_id, skill_id, release_id, operation,
                    from_state_json, to_state_json, actor_user_id)
            VALUES (#{ownerUserId}, #{skillId}, #{releaseId}, #{operation},
                    #{fromStateJson}::jsonb, #{toStateJson}::jsonb, #{actorUserId})
            """)
    int insertLog(SkillOperationLogEntity entity);
}

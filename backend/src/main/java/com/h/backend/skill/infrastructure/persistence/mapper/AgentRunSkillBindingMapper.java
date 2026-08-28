package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.AgentRunSkillBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentRunSkillBindingMapper extends BaseMapper<AgentRunSkillBindingEntity> {

    @Select("""
            SELECT * FROM agent_run_skill_bindings
            WHERE run_id = #{runId}
            ORDER BY source_type ASC, skill_key ASC
            """)
    List<AgentRunSkillBindingEntity> selectByRunId(@Param("runId") long runId);
}

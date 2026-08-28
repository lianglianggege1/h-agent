package com.h.backend.skill.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.skill.infrastructure.persistence.entity.SkillOperationLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillOperationLogMapper extends BaseMapper<SkillOperationLogEntity> {
}

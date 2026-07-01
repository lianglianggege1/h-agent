package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.SystemPromptEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemPromptMapper extends BaseMapper<SystemPromptEntity> {
}

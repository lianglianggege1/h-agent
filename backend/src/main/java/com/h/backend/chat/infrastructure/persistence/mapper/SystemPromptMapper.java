package com.h.backend.chat.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.chat.infrastructure.persistence.entity.SystemPromptEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemPromptMapper extends BaseMapper<SystemPromptEntity> {
    // Lock a stable row even when this user has no prompts yet.
    @Select("SELECT id FROM users WHERE id = #{userId} FOR UPDATE")
    Long lockOwner(@Param("userId") Long userId);
}

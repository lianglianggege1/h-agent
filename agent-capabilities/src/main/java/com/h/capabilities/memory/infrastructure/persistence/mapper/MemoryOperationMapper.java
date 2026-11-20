package com.h.capabilities.memory.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.capabilities.memory.infrastructure.persistence.entity.MemoryOperationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemoryOperationMapper extends BaseMapper<MemoryOperationEntity> {
}

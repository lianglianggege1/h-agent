package com.h.backend.automation.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationAuditEventEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AutomationAuditEventMapper extends BaseMapper<AutomationAuditEventEntity> {
}

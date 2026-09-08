package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AutomationAuditEntry;
import com.h.backend.automation.application.AutomationAuditRecorder;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationAuditEventEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationAuditEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 审计事件落库。审计写失败只记录日志、不阻断主流程；
 * detailJson 由应用层保证不含 token、Cookie 等敏感信息。
 */
@Component
public class AutomationAuditRecorderImpl implements AutomationAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AutomationAuditRecorderImpl.class);

    private final AutomationAuditEventMapper mapper;

    public AutomationAuditRecorderImpl(AutomationAuditEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(AutomationAuditEntry entry) {
        try {
            AutomationAuditEventEntity entity = new AutomationAuditEventEntity();
            entity.setUserId(entry.userId());
            entity.setAction(entry.action());
            entity.setTargetType(entry.targetType());
            entity.setTargetId(entry.targetId());
            entity.setBeforeRevision(entry.beforeRevision());
            entity.setAfterRevision(entry.afterRevision());
            entity.setRequestKey(truncate(entry.requestKey(), 120));
            entity.setDetailJson(truncate(entry.detailJson(), 4000));
            LocalDateTime at = entry.occurredAt() == null
                    ? LocalDateTime.now(ZoneOffset.UTC)
                    : LocalDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC);
            entity.setCreatedAt(at);
            mapper.insert(entity);
        } catch (RuntimeException error) {
            log.warn("Automation audit record failed action={} target={}: {}",
                    entry.action(), entry.targetId(), error.getMessage());
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

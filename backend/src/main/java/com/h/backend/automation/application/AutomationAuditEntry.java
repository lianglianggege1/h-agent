package com.h.backend.automation.application;

import java.time.Instant;

/** 自动化领域审计事件。detailJson 由应用层序列化，不得包含 token、Cookie 等敏感信息。 */
public record AutomationAuditEntry(
        Long userId,
        String action,
        String targetType,
        String targetId,
        Long beforeRevision,
        Long afterRevision,
        String requestKey,
        String detailJson,
        Instant occurredAt
) {
    public static AutomationAuditEntry of(
            Long userId, String action, String targetType, String targetId,
            Long beforeRevision, Long afterRevision, Instant occurredAt
    ) {
        return new AutomationAuditEntry(userId, action, targetType, targetId,
                beforeRevision, afterRevision, null, null, occurredAt);
    }
}

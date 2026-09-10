package com.h.backend.automation.domain;

import java.time.Instant;

/**
 * 聊天内写操作的提案。PENDING 24 小时内可确认；确认时重新校验所有权与基线版本；
 * 重复确认返回首次结果；过期或基线漂移后必须重新生成。
 */
public record AutomationProposal(
        String id,
        Long userId,
        String taskId,
        String action,
        String payloadJson,
        Long baseTaskRevision,
        String status,
        String sourceSessionId,
        String createdVia,
        String idempotencyKey,
        String resultTaskId,
        Long sourceAgentRunId,
        Instant expiresAt,
        Instant confirmedAt,
        Long confirmedBy,
        Instant createdAt,
        Instant updatedAt
) {
}

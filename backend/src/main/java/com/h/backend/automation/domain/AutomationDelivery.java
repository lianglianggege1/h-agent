package com.h.backend.automation.domain;

import java.time.Instant;

/**
 * 一条投递意图：Run 终态事务中按冻结的投递目标快照创建，投递器独立领取、重试与死信。
 * targetJson 为目的地快照（如 SESSION 的 sessionId）；payloadJson 为结果卡片内容快照。
 */
public record AutomationDelivery(
        String id,
        String runId,
        String taskId,
        Long userId,
        String sinkType,
        String targetJson,
        String payloadJson,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        String lastError,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt
) {
}

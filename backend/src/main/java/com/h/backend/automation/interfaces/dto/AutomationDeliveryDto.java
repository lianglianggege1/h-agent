package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationDelivery;

import java.time.Instant;

public record AutomationDeliveryDto(
        String id,
        String runId,
        String sinkType,
        String status,
        int attemptCount,
        String lastError,
        Instant nextAttemptAt,
        Instant deliveredAt,
        Instant createdAt
) {
    public static AutomationDeliveryDto from(AutomationDelivery delivery) {
        return new AutomationDeliveryDto(
                delivery.id(), delivery.runId(), delivery.sinkType(), delivery.status(),
                delivery.attemptCount(), delivery.lastError(), delivery.nextAttemptAt(),
                delivery.deliveredAt(), delivery.createdAt()
        );
    }
}

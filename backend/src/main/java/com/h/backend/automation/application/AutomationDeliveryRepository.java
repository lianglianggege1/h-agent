package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDelivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationDeliveryRepository {

    /**
     * Run 终态落库与投递意图创建在同一事务：只有 Run 从 RUNNING/CANCEL_REQUESTED 成功流转到
     * 终态时才创建投递行，崩溃重放不会重复建单。
     */
    boolean completeRunAndCreateDeliveries(
            String runId, String status, Instant finishedAt, String sessionId,
            String output, String errorMessage, Instant cancelRequestedAt,
            List<AutomationDelivery> deliveries
    );

    List<AutomationDelivery> claimDue(Instant now, int limit, String leaseOwner, Duration leaseDuration);

    void markDelivered(String deliveryId, String leaseOwner, Instant now);

    void markRetrying(String deliveryId, String leaseOwner, String errorMessage, Instant nextAttemptAt, Instant now);

    void markDeadLetter(String deliveryId, String leaseOwner, String errorMessage, Instant now);

    List<AutomationDelivery> listByRun(Long userId, String runId);

    Optional<AutomationDelivery> findOwned(Long userId, String deliveryId);
}

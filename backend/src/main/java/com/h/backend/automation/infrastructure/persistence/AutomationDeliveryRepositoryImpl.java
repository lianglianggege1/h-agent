package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AutomationDeliveryRepository;
import com.h.backend.automation.domain.AutomationDelivery;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationDeliveryEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationDeliveryMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationRunMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AutomationDeliveryRepositoryImpl implements AutomationDeliveryRepository {

    private final AutomationDeliveryMapper deliveryMapper;
    private final AutomationRunMapper runMapper;

    public AutomationDeliveryRepositoryImpl(
            AutomationDeliveryMapper deliveryMapper,
            AutomationRunMapper runMapper
    ) {
        this.deliveryMapper = deliveryMapper;
        this.runMapper = runMapper;
    }

    @Override
    @Transactional
    public boolean completeRunAndCreateDeliveries(
            String runId,
            String status,
            Instant finishedAt,
            String sessionId,
            String output,
            String errorMessage,
            Instant cancelRequestedAt,
            List<AutomationDelivery> deliveries
    ) {
        int changed = runMapper.complete(runId, status, toLocal(finishedAt), sessionId,
                output, errorMessage, toLocal(cancelRequestedAt));
        if (changed == 0) {
            return false;
        }
        for (AutomationDelivery delivery : deliveries) {
            deliveryMapper.insert(toEntity(delivery));
        }
        return true;
    }

    @Override
    public List<AutomationDelivery> claimDue(Instant now, int limit, String leaseOwner, Duration leaseDuration) {
        return deliveryMapper.claimDue(toLocal(now), limit, leaseOwner, toLocal(now.plus(leaseDuration)))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void markDelivered(String deliveryId, String leaseOwner, Instant now) {
        deliveryMapper.markDelivered(deliveryId, leaseOwner, toLocal(now));
    }

    @Override
    public void markRetrying(String deliveryId, String leaseOwner, String errorMessage,
                             Instant nextAttemptAt, Instant now) {
        deliveryMapper.markRetrying(deliveryId, leaseOwner, truncate(errorMessage, 900),
                toLocal(nextAttemptAt), toLocal(now));
    }

    @Override
    public void markDeadLetter(String deliveryId, String leaseOwner, String errorMessage, Instant now) {
        deliveryMapper.markDeadLetter(deliveryId, leaseOwner, truncate(errorMessage, 900), toLocal(now));
    }

    @Override
    public List<AutomationDelivery> listByRun(Long userId, String runId) {
        return deliveryMapper.selectByRun(userId, runId).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<AutomationDelivery> findOwned(Long userId, String deliveryId) {
        return Optional.ofNullable(deliveryMapper.selectOwned(userId, deliveryId)).map(this::toDomain);
    }

    private AutomationDeliveryEntity toEntity(AutomationDelivery delivery) {
        AutomationDeliveryEntity entity = new AutomationDeliveryEntity();
        entity.setId(delivery.id());
        entity.setRunId(delivery.runId());
        entity.setTaskId(delivery.taskId());
        entity.setUserId(delivery.userId());
        entity.setSinkType(delivery.sinkType());
        entity.setTargetJson(delivery.targetJson());
        entity.setPayloadJson(delivery.payloadJson());
        entity.setStatus(delivery.status());
        entity.setAttemptCount(delivery.attemptCount());
        entity.setNextAttemptAt(toLocal(delivery.nextAttemptAt()));
        entity.setLeaseOwner(delivery.leaseOwner());
        entity.setLeaseUntil(toLocal(delivery.leaseUntil()));
        entity.setLastError(delivery.lastError());
        entity.setDeliveredAt(toLocal(delivery.deliveredAt()));
        entity.setCreatedAt(toLocal(delivery.createdAt()));
        entity.setUpdatedAt(toLocal(delivery.updatedAt()));
        return entity;
    }

    private AutomationDelivery toDomain(AutomationDeliveryEntity entity) {
        return new AutomationDelivery(
                entity.getId(), entity.getRunId(), entity.getTaskId(), entity.getUserId(),
                entity.getSinkType(), entity.getTargetJson(), entity.getPayloadJson(),
                entity.getStatus(), entity.getAttemptCount() == null ? 0 : entity.getAttemptCount(),
                toInstant(entity.getNextAttemptAt()), entity.getLeaseOwner(),
                toInstant(entity.getLeaseUntil()), entity.getLastError(),
                toInstant(entity.getDeliveredAt()), toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt())
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}

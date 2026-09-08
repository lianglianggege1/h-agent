package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationDelivery;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationDeliveryStatus;
import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 投递模块：Run 终态事务中规划投递意图，投递器独立租约领取。
 * SESSION 由平台控制（用户可见结果至多一次为目标）；失败只重试投递、不重跑 Run，
 * 超过上限进入死信。NONE 在建单时即 SKIPPED。
 */
@Service
public class DeliveryModule {

    private static final Logger log = LoggerFactory.getLogger(DeliveryModule.class);

    private final AutomationDeliveryRepository repository;
    private final Map<String, AutomationDeliverySinkHandler> handlers;
    private final AutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String leaseOwner = ProcessHandle.current().pid() + "-delivery-" + UUID.randomUUID();

    @Autowired
    public DeliveryModule(
            AutomationDeliveryRepository repository,
            List<AutomationDeliverySinkHandler> handlers,
            AutomationProperties properties,
            ObjectMapper objectMapper
    ) {
        this(repository, handlers, properties, objectMapper, Clock.systemUTC());
    }

    DeliveryModule(
            AutomationDeliveryRepository repository,
            List<AutomationDeliverySinkHandler> handlers,
            AutomationProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.repository = repository;
        this.handlers = new java.util.HashMap<>();
        for (AutomationDeliverySinkHandler handler : handlers) {
            this.handlers.put(handler.sinkType(), handler);
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Run 终态时调用：按接纳时冻结的任务投递目标规划投递意图（与 Run 终态同事务落库）。 */
    public List<AutomationDelivery> planTerminalDeliveries(
            ExecutionSpec spec,
            AutomationRun run,
            String status,
            String sessionId,
            String output,
            String errorMessage,
            Instant finishedAt
    ) {
        Instant now = clock.instant();
        String payload = cardPayload(spec, run, status, sessionId, output, errorMessage, finishedAt);
        String sink = spec.deliverySink() == null
                ? AutomationDeliverySink.NONE.name() : spec.deliverySink();
        if (AutomationDeliverySink.SESSION.name().equals(sink) && spec.deliverySessionId() != null) {
            String target = json(Map.of("sessionId", spec.deliverySessionId()));
            return List.of(delivery(run, spec, sink, target, payload,
                    AutomationDeliveryStatus.PENDING.name(), null, now));
        }
        // NONE 或 SESSION 但无可投递会话：建单即跳过，仅管理页可见。
        String reason = AutomationDeliverySink.SESSION.name().equals(sink)
                ? "{\"reason\":\"NO_TARGET_SESSION\"}" : "{}";
        return List.of(delivery(run, spec, AutomationDeliverySink.NONE.name(), reason, payload,
                AutomationDeliveryStatus.SKIPPED.name(), now, now));
    }

    public List<AutomationDelivery> listDeliveries(Long userId, String runId) {
        return repository.listByRun(userId, runId);
    }

    public void dispatchPending() {
        Instant now = clock.instant();
        List<AutomationDelivery> due = repository.claimDue(
                now, Math.max(1, properties.getDelivery().getBatchSize()),
                leaseOwner, properties.getDelivery().getLeaseDuration()
        );
        for (AutomationDelivery delivery : due) {
            dispatch(delivery);
        }
    }

    private void dispatch(AutomationDelivery delivery) {
        Instant now = clock.instant();
        AutomationDeliverySinkHandler handler = handlers.get(delivery.sinkType());
        if (handler == null) {
            repository.markDeadLetter(delivery.id(), leaseOwner,
                    "没有可用的投递器：" + delivery.sinkType(), now);
            return;
        }
        try {
            handler.deliver(delivery);
            repository.markDelivered(delivery.id(), leaseOwner, now);
        } catch (Exception error) {
            String message = safeMessage(error);
            int attempts = delivery.attemptCount() + 1;
            Instant retryAt = now.plus(backoff(attempts));
            if (attempts >= properties.getDelivery().getMaxAttempts()) {
                repository.markDeadLetter(delivery.id(), leaseOwner, message, now);
                log.warn("Delivery dead-lettered deliveryId={} runId={} sink={} attempts={}: {}",
                        delivery.id(), delivery.runId(), delivery.sinkType(), attempts, message);
            } else {
                repository.markRetrying(delivery.id(), leaseOwner, message, retryAt, now);
                log.info("Delivery retry scheduled deliveryId={} runId={} sink={} attempts={} at={}: {}",
                        delivery.id(), delivery.runId(), delivery.sinkType(), attempts, retryAt, message);
            }
        }
    }

    private Duration backoff(int attempt) {
        long baseSeconds = Math.min(300, 5L << Math.min(Math.max(attempt - 1, 0), 6));
        long jitter = ThreadLocalRandom.current().nextLong(-Math.max(1, baseSeconds / 5),
                Math.max(2, baseSeconds / 5) + 1);
        return Duration.ofSeconds(Math.max(1, baseSeconds + jitter));
    }

    private AutomationDelivery delivery(
            AutomationRun run, ExecutionSpec spec, String sink, String targetJson,
            String payloadJson, String status, Instant nextAttemptAt, Instant now
    ) {
        return new AutomationDelivery(
                UUID.randomUUID().toString(), run.id(), spec.taskId(), spec.userId(), sink,
                targetJson, payloadJson, status, 0, nextAttemptAt,
                null, null, null, null, now, now
        );
    }

    private String cardPayload(
            ExecutionSpec spec,
            AutomationRun run,
            String status,
            String sessionId,
            String output,
            String errorMessage,
            Instant finishedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", spec.taskId());
        payload.put("taskName", spec.taskName());
        payload.put("runId", run.id());
        payload.put("status", status);
        payload.put("triggerType", run.triggerType());
        payload.put("scheduledFor", run.scheduledFor() == null ? null : run.scheduledFor().toString());
        payload.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        payload.put("runSessionId", sessionId);
        payload.put("outputExcerpt", excerpt(output, 2000));
        payload.put("errorMessage", excerpt(errorMessage, 500));
        return json(payload);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("无法序列化投递内容", error);
        }
    }

    private static String excerpt(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = "投递失败";
        }
        return message.length() <= 900 ? message : message.substring(0, 900);
    }
}

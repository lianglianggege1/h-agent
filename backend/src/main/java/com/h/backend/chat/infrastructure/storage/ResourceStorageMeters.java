package com.h.backend.chat.infrastructure.storage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 资源存储 Micrometer 指标（统一 Trace 设计 §10.7）：替换原进程内
 * LongAdder + {@code snapshot()} 形态，经 Prometheus scrape 暴露，
 * 不依赖 Agent Trace 采样，也不发往 Langfuse。
 *
 * <p>Meter 一览：
 * <ul>
 *   <li>{@code h.agent.resource.storage.operation.duration} Timer（秒，带 percentile
 *       histogram 供 p95/p99 告警），tags operation/outcome/error.kind：
 *       save/open/discard 的次数、延迟与失败分布（Timer count 即操作次数，
 *       不再维护同义 Counter）；</li>
 *   <li>{@code h.agent.resource.storage.object.size} DistributionSummary（byte），
 *       tag operation=save：save 成功后确认的实际对象字节量；</li>
 *   <li>{@code h.agent.resource.storage.compensation} Counter，tags
 *       outcome/error.kind：事务回滚补偿删除的成功/失败。</li>
 * </ul>
 *
 * <p>Tag 取值全部是有界枚举（基数约束，设计 §10.6）：operation =
 * save|open|discard；outcome = success|failure|rejected；error.kind =
 * none|not_found|size_limit|unavailable|io_error|range。禁止把 resourceId、
 * storageKey、bucket、userId、sessionId 或 endpoint 放入 label。
 *
 * <p>no-throw 契约（设计 §10.7）：本类所有方法绝不抛出——MeterRegistry 或
 * Prometheus 不可用不得改变资源操作结果。Metrics 关闭（无 registry）时由空
 * {@link CompositeMeterRegistry} 提供进程内 no-op 行为，调用方不判空、不分支。
 * 本类不暴露计数 getter 或 snapshot()，测试通过注入
 * {@link io.micrometer.core.instrument.simple.SimpleMeterRegistry} 查询 Meter。
 */
public final class ResourceStorageMeters {

    static final String OPERATION_DURATION = "h.agent.resource.storage.operation.duration";
    static final String OBJECT_SIZE = "h.agent.resource.storage.object.size";
    static final String COMPENSATION = "h.agent.resource.storage.compensation";

    private static final String TAG_OPERATION = "operation";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_ERROR_KIND = "error.kind";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_FAILURE = "failure";
    private static final String OUTCOME_REJECTED = "rejected";
    private static final String ERROR_KIND_NONE = "none";
    private static final String ERROR_KIND_RANGE = "range";

    private final MeterRegistry registry;

    public ResourceStorageMeters(MeterRegistry registry) {
        this.registry = registry == null ? new CompositeMeterRegistry() : registry;
    }

    /**
     * 开始一次 save/open/discard 测量：单调时钟（registry clock）计时，
     * 调用处以 success/failure/rejected 结束；遗漏终态时 close() 安全兜底。
     */
    public StorageMeasurement start(StorageOperation operation) {
        return new TimerMeasurement(operation);
    }

    /** Coordinator 事务回滚补偿删除成功。 */
    public void recordCompensationSuccess() {
        runQuietly(() -> compensationCounter(OUTCOME_SUCCESS, ERROR_KIND_NONE).increment());
    }

    /** Coordinator 事务回滚补偿删除失败（可能残留孤儿对象，配合 ERROR 级脱敏日志）。 */
    public void recordCompensationFailure(ResourceStorageErrorKind kind) {
        String errorKind = tagOf(kind == null ? ResourceStorageErrorKind.IO_ERROR : kind);
        runQuietly(() -> compensationCounter(OUTCOME_FAILURE, errorKind).increment());
    }

    private Counter compensationCounter(String outcome, String errorKind) {
        return Counter.builder(COMPENSATION)
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_ERROR_KIND, errorKind)
                .register(registry);
    }

    private Timer operationTimer(StorageOperation operation, String outcome, String errorKind) {
        return Timer.builder(OPERATION_DURATION)
                .tag(TAG_OPERATION, operation.tagValue())
                .tag(TAG_OUTCOME, outcome)
                .tag(TAG_ERROR_KIND, errorKind)
                .publishPercentileHistogram()
                .register(registry);
    }

    private DistributionSummary objectSizeSummary() {
        return DistributionSummary.builder(OBJECT_SIZE)
                .tag(TAG_OPERATION, StorageOperation.SAVE.tagValue())
                .register(registry);
    }

    private static String tagOf(ResourceStorageErrorKind kind) {
        return switch (kind) {
            case NOT_FOUND -> "not_found";
            case SIZE_LIMIT -> "size_limit";
            case UNAVAILABLE -> "unavailable";
            case IO_ERROR -> "io_error";
        };
    }

    /** no-throw 兜底（设计 §10.7）：指标基础设施异常绝不外抛。 */
    private static void runQuietly(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // MeterRegistry 冲突/关闭等异常不改变资源操作结果。
        }
    }

    private final class TimerMeasurement implements StorageMeasurement {

        private final StorageOperation operation;
        private final long startNanos;
        private final AtomicBoolean terminated = new AtomicBoolean();

        TimerMeasurement(StorageOperation operation) {
            this.operation = operation;
            this.startNanos = registry.config().clock().monotonicTime();
        }

        @Override
        public void success() {
            success(-1L);
        }

        @Override
        public void success(long actualBytes) {
            if (!tryTerminate()) {
                return;
            }
            long elapsedNanos = registry.config().clock().monotonicTime() - startNanos;
            runQuietly(() -> operationTimer(operation, OUTCOME_SUCCESS, ERROR_KIND_NONE)
                    .record(Duration.ofNanos(elapsedNanos)));
            if (operation == StorageOperation.SAVE && actualBytes >= 0L) {
                runQuietly(() -> objectSizeSummary().record(actualBytes));
            }
        }

        @Override
        public void failure(ResourceStorageErrorKind kind) {
            ResourceStorageErrorKind errorKind =
                    kind == null ? ResourceStorageErrorKind.IO_ERROR : kind;
            if (!tryTerminate()) {
                return;
            }
            long elapsedNanos = registry.config().clock().monotonicTime() - startNanos;
            runQuietly(() -> operationTimer(operation, OUTCOME_FAILURE, tagOf(errorKind))
                    .record(Duration.ofNanos(elapsedNanos)));
        }

        @Override
        public void rejected(StorageRejectionKind kind) {
            if (!tryTerminate()) {
                return;
            }
            long elapsedNanos = registry.config().clock().monotonicTime() - startNanos;
            runQuietly(() -> operationTimer(operation, OUTCOME_REJECTED, ERROR_KIND_RANGE)
                    .record(Duration.ofNanos(elapsedNanos)));
        }

        @Override
        public void close() {
            failure(ResourceStorageErrorKind.IO_ERROR);
        }

        private boolean tryTerminate() {
            return terminated.compareAndSet(false, true);
        }
    }
}

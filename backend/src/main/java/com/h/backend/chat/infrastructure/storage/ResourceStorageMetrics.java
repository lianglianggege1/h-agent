package com.h.backend.chat.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 资源存储最小可观测性（新计划 §10 任务 6）：进程内计数与补偿删除失败告警。
 *
 * <p>刻意不引入 micrometer/actuator/health（计划 §1.2 明确不实施）——
 * 只做 LongAdder 计数 + 结构化日志，满足"失败即记、成功不刷屏"的最小可用性：
 * <ul>
 *   <li>save / open / discard 成功计数；失败按 {@link ResourceStorageErrorKind} 细分
 *       （{@link ConcurrentMap} + {@link LongAdder}，读多写少场景 O(1) 无锁累加）。</li>
 *   <li>Coordinator 补偿 discard 的成功/失败单独计数（区别于 Adapter 层 discard：
 *       补偿路径 = 事务回滚后的 best-effort 删除，失败意味着可能残留孤儿对象）。</li>
 *   <li>补偿 discard 失败输出 ERROR 级结构化告警：只含 operation=discard、errorKind、
 *       resourceId 与 key 尾段（uuid.ext）；<b>绝不</b>包含完整 object key、secret、
 *       endpoint、签名 URL 或 SDK 异常消息（计划不变量 17 / 任务 6 日志纪律）。</li>
 *   <li>{@link #snapshot()} 返回不可变快照，供日志与测试断言，与后续更新解耦。</li>
 * </ul>
 *
 * <p>线程安全：所有计数器为 {@link LongAdder}；按 kind 细分表为
 * {@link ConcurrentHashMap}，首次遇到某 kind 时惰性建 Adder，无全局锁。
 */
@Component
public class ResourceStorageMetrics {

    private static final Logger log = LoggerFactory.getLogger(ResourceStorageMetrics.class);

    private final LongAdder saveSuccess = new LongAdder();
    private final LongAdder openSuccess = new LongAdder();
    private final LongAdder discardSuccess = new LongAdder();

    private final ConcurrentMap<ResourceStorageErrorKind, LongAdder> saveFailures = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResourceStorageErrorKind, LongAdder> openFailures = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResourceStorageErrorKind, LongAdder> discardFailures = new ConcurrentHashMap<>();

    private final LongAdder compensatedDiscardSuccess = new LongAdder();
    private final LongAdder compensatedDiscardFailure = new LongAdder();

    // ------------------------------------------------------------------
    // Adapter 层埋点：save / open / discard
    // ------------------------------------------------------------------

    public void recordSaveSuccess() {
        saveSuccess.increment();
    }

    public void recordSaveFailure(ResourceStorageErrorKind kind) {
        incrementKind(saveFailures, kind);
    }

    public void recordOpenSuccess() {
        openSuccess.increment();
    }

    public void recordOpenFailure(ResourceStorageErrorKind kind) {
        incrementKind(openFailures, kind);
    }

    public void recordDiscardSuccess() {
        discardSuccess.increment();
    }

    public void recordDiscardFailure(ResourceStorageErrorKind kind) {
        incrementKind(discardFailures, kind);
    }

    // ------------------------------------------------------------------
    // Coordinator 补偿路径埋点（事务回滚后的 best-effort discard）
    // ------------------------------------------------------------------

    public void recordCompensatedDiscardSuccess() {
        compensatedDiscardSuccess.increment();
    }

    /**
     * 补偿 discard 失败：计数 + ERROR 级结构化告警。
     *
     * @param resourceId 资源 ID（uuid，允许 null——排障定位为空时输出占位符）
     * @param keySuffix  object key 尾段（uuid.ext）；调用方必须保证已截断，
     *                   完整 key 不得进入日志
     * @param failure    原始异常；只取错误类别，消息与 cause 不进入日志
     */
    public void recordCompensatedDiscardFailure(String resourceId, String keySuffix, Throwable failure) {
        compensatedDiscardFailure.increment();
        ResourceStorageErrorKind kind = failure instanceof ResourceStorageException storageException
                ? storageException.kind()
                : ResourceStorageErrorKind.IO_ERROR;
        // 脱敏告警（计划不变量 17）：固定字段，不含完整 key/secret/endpoint/SDK 异常全文。
        log.error(
                "资源补偿删除失败，需人工关注孤儿对象 operation=discard errorKind={} resourceId={} storageKeySuffix={}",
                kind,
                resourceId == null ? "-" : resourceId,
                keySuffix == null || keySuffix.isBlank() ? "-" : keySuffix);
    }

    // ------------------------------------------------------------------
    // 计数读取
    // ------------------------------------------------------------------

    public long compensatedDiscardSuccessCount() {
        return compensatedDiscardSuccess.sum();
    }

    public long compensatedDiscardFailureCount() {
        return compensatedDiscardFailure.sum();
    }

    public StorageMetricsSnapshot snapshot() {
        return new StorageMetricsSnapshot(
                saveSuccess.sum(),
                openSuccess.sum(),
                discardSuccess.sum(),
                copyKindCounts(saveFailures),
                copyKindCounts(openFailures),
                copyKindCounts(discardFailures),
                compensatedDiscardSuccess.sum(),
                compensatedDiscardFailure.sum());
    }

    private static void incrementKind(
            ConcurrentMap<ResourceStorageErrorKind, LongAdder> counters,
            ResourceStorageErrorKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        counters.computeIfAbsent(kind, ignored -> new LongAdder()).increment();
    }

    private static Map<ResourceStorageErrorKind, Long> copyKindCounts(
            ConcurrentMap<ResourceStorageErrorKind, LongAdder> counters) {
        return counters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sum()));
    }

    /** 一次性快照（record）：字段不可变、Map 不可变，与后续计数更新解耦。 */
    public record StorageMetricsSnapshot(
            long saveSuccess,
            long openSuccess,
            long discardSuccess,
            Map<ResourceStorageErrorKind, Long> saveFailuresByKind,
            Map<ResourceStorageErrorKind, Long> openFailuresByKind,
            Map<ResourceStorageErrorKind, Long> discardFailuresByKind,
            long compensatedDiscardSuccess,
            long compensatedDiscardFailure
    ) {
        public long saveFailureTotal() {
            return total(saveFailuresByKind);
        }

        public long openFailureTotal() {
            return total(openFailuresByKind);
        }

        public long discardFailureTotal() {
            return total(discardFailuresByKind);
        }

        private static long total(Map<ResourceStorageErrorKind, Long> counters) {
            return counters.values().stream().mapToLong(Long::longValue).sum();
        }
    }
}

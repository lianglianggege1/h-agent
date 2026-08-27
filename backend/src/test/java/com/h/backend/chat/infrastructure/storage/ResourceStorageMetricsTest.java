package com.h.backend.chat.infrastructure.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ResourceStorageMetrics} 单元测试（新计划 §10 任务 6：最小可观测性——计数与告警）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>save/open/discard 成功与失败计数，failure 按 {@link ResourceStorageErrorKind} 细分；</li>
 *   <li>补偿 discard（Coordinator 事务回滚补偿语义）成功/失败计数；</li>
 *   <li>补偿 discard 失败的 ERROR 级结构化告警：必须含 operation=discard、errorKind、
 *       resourceId 与 key 尾段；绝不包含完整 object key、secret、endpoint 或 SDK 异常消息
 *       （计划不变量 17 / 任务 6 日志纪律）。日志用 Logback ListAppender 捕获断言。</li>
 *   <li>snapshot() 与后续更新解耦且不可变。</li>
 * </ul>
 */
class ResourceStorageMetricsTest {

    private static final String RESOURCE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String KEY_SUFFIX = "550e8400-e29b-41d4-a716-446655440000.pdf";
    private static final String FULL_STORAGE_KEY =
            "resources/v1/files/2026/08/550e8400-e29b-41d4-a716-446655440000.pdf";
    private static final String SECRET = "minio-secret-value-should-never-appear";
    private static final String ENDPOINT_HOST = "169.254.140.78";

    private ResourceStorageMetrics metrics;
    private Logger metricsLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        metrics = new ResourceStorageMetrics();
        metricsLogger = (Logger) LoggerFactory.getLogger(ResourceStorageMetrics.class);
        appender = new ListAppender<>();
        appender.start();
        metricsLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        metricsLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void successCountersTrackSaveOpenAndDiscard() {
        metrics.recordSaveSuccess();
        metrics.recordSaveSuccess();
        metrics.recordOpenSuccess();
        metrics.recordDiscardSuccess();

        ResourceStorageMetrics.StorageMetricsSnapshot snapshot = metrics.snapshot();

        assertThat(snapshot.saveSuccess()).isEqualTo(2L);
        assertThat(snapshot.openSuccess()).isEqualTo(1L);
        assertThat(snapshot.discardSuccess()).isEqualTo(1L);
        assertThat(snapshot.saveFailureTotal()).isZero();
        assertThat(snapshot.openFailureTotal()).isZero();
        assertThat(snapshot.discardFailureTotal()).isZero();
    }

    @Test
    void failureCountersAreBrokenDownByErrorKind() {
        metrics.recordSaveFailure(ResourceStorageErrorKind.SIZE_LIMIT);
        metrics.recordSaveFailure(ResourceStorageErrorKind.SIZE_LIMIT);
        metrics.recordSaveFailure(ResourceStorageErrorKind.UNAVAILABLE);
        metrics.recordOpenFailure(ResourceStorageErrorKind.NOT_FOUND);
        metrics.recordDiscardFailure(ResourceStorageErrorKind.UNAVAILABLE);

        ResourceStorageMetrics.StorageMetricsSnapshot snapshot = metrics.snapshot();

        assertThat(snapshot.saveFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.SIZE_LIMIT, 2L)
                .containsEntry(ResourceStorageErrorKind.UNAVAILABLE, 1L)
                .hasSize(2);
        assertThat(snapshot.saveFailureTotal()).isEqualTo(3L);
        assertThat(snapshot.openFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.NOT_FOUND, 1L);
        assertThat(snapshot.openFailureTotal()).isEqualTo(1L);
        assertThat(snapshot.discardFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.UNAVAILABLE, 1L);
        assertThat(snapshot.discardFailureTotal()).isEqualTo(1L);
        assertThat(snapshot.saveSuccess()).isZero();
    }

    @Test
    void compensatedDiscardCountersTrackCoordinatorOutcomes() {
        metrics.recordCompensatedDiscardSuccess();
        metrics.recordCompensatedDiscardSuccess();
        metrics.recordCompensatedDiscardFailure(
                RESOURCE_ID, KEY_SUFFIX,
                new ResourceStorageException(ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用"));

        assertThat(metrics.compensatedDiscardSuccessCount()).isEqualTo(2L);
        assertThat(metrics.compensatedDiscardFailureCount()).isEqualTo(1L);
        assertThat(metrics.snapshot().compensatedDiscardSuccess()).isEqualTo(2L);
        assertThat(metrics.snapshot().compensatedDiscardFailure()).isEqualTo(1L);
    }

    @Test
    void compensatedDiscardFailureLogsErrorAlertWithSanitizedContent() {
        ResourceStorageException failure = new ResourceStorageException(
                ResourceStorageErrorKind.UNAVAILABLE,
                "存储服务暂时不可用",
                new RuntimeException("leaky sdk detail " + ENDPOINT_HOST + " " + SECRET));

        metrics.recordCompensatedDiscardFailure(RESOURCE_ID, KEY_SUFFIX, failure);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        String message = event.getFormattedMessage();
        // 必含字段：operation=discard、errorKind、resourceId、key 尾段
        assertThat(message).contains("operation=discard");
        assertThat(message).contains("errorKind=UNAVAILABLE");
        assertThat(message).contains(RESOURCE_ID);
        assertThat(message).contains(KEY_SUFFIX);
        // 脱敏（计划不变量 17）：完整 key、endpoint、SDK 异常细节、secret 不得出现
        assertThat(message)
                .doesNotContain(FULL_STORAGE_KEY)
                .doesNotContain("resources/v1")
                .doesNotContain(ENDPOINT_HOST)
                .doesNotContain("leaky sdk detail")
                .doesNotContain(SECRET);
    }

    @Test
    void nonStorageFailureFallsBackToIoErrorWithSanitizedAlert() {
        metrics.recordCompensatedDiscardFailure(
                RESOURCE_ID, KEY_SUFFIX, new IllegalStateException("boom " + SECRET));

        assertThat(metrics.compensatedDiscardFailureCount()).isEqualTo(1L);
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("errorKind=IO_ERROR");
        assertThat(message).doesNotContain(SECRET).doesNotContain("boom");
    }

    @Test
    void failureCountersRejectNullKind() {
        assertThatThrownBy(() -> metrics.recordSaveFailure(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.recordOpenFailure(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.recordDiscardFailure(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void snapshotIsDecoupledFromLaterUpdatesAndImmutable() {
        metrics.recordSaveFailure(ResourceStorageErrorKind.UNAVAILABLE);
        ResourceStorageMetrics.StorageMetricsSnapshot first = metrics.snapshot();
        metrics.recordSaveFailure(ResourceStorageErrorKind.UNAVAILABLE);

        assertThat(first.saveFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.UNAVAILABLE, 1L);
        assertThat(metrics.snapshot().saveFailuresByKind())
                .containsEntry(ResourceStorageErrorKind.UNAVAILABLE, 2L);
        assertThatThrownBy(() -> first.saveFailuresByKind()
                .put(ResourceStorageErrorKind.NOT_FOUND, 5L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.h.backend.chat.infrastructure.storage;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ResourceStorageMeters} 契约测试（统一 Trace 设计 §10.7）：通过注入
 * {@link SimpleMeterRegistry} 查询 Meter 结果，验证 Meter 名称、有界 tag 取值、
 * 单调时钟计时、终态 first-wins 与 no-throw 契约。
 */
class ResourceStorageMetersTest {

    @Test
    void saveSuccessRecordsDurationAndConfirmedObjectSize() {
        Clock clock = mock(Clock.class);
        // 单调时钟：start 时 1ms，终态时 2.5ms → 1.5ms 计时
        when(clock.monotonicTime()).thenReturn(1_000_000L, 2_500_000L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.start(StorageOperation.SAVE).success(123L);

        Timer timer = registry.get(ResourceStorageMeters.OPERATION_DURATION)
                .tag("operation", "save")
                .tag("outcome", "success")
                .tag("error.kind", "none")
                .timer();
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1.5d);
        assertThat(registry.get(ResourceStorageMeters.OBJECT_SIZE)
                .tag("operation", "save")
                .summary().totalAmount()).isEqualTo(123L);
    }

    @Test
    void openAndDiscardSuccessDoNotRecordObjectSize() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.start(StorageOperation.OPEN).success();
        meters.start(StorageOperation.DISCARD).success();

        assertThat(operationTimerCount(registry, "open", "success", "none")).isEqualTo(1L);
        assertThat(operationTimerCount(registry, "discard", "success", "none")).isEqualTo(1L);
        assertThat(registry.find(ResourceStorageMeters.OBJECT_SIZE).summary()).isNull();
    }

    @Test
    void failuresAreTaggedByBoundedErrorKind() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.start(StorageOperation.SAVE).failure(ResourceStorageErrorKind.SIZE_LIMIT);
        meters.start(StorageOperation.SAVE).failure(ResourceStorageErrorKind.UNAVAILABLE);
        meters.start(StorageOperation.OPEN).failure(ResourceStorageErrorKind.NOT_FOUND);
        meters.start(StorageOperation.DISCARD).failure(ResourceStorageErrorKind.IO_ERROR);
        meters.start(StorageOperation.OPEN).failure(null);

        assertThat(operationTimerCount(registry, "save", "failure", "size_limit")).isEqualTo(1L);
        assertThat(operationTimerCount(registry, "save", "failure", "unavailable")).isEqualTo(1L);
        assertThat(operationTimerCount(registry, "open", "failure", "not_found")).isEqualTo(1L);
        assertThat(operationTimerCount(registry, "discard", "failure", "io_error")).isEqualTo(1L);
        // null kind 安全落到 io_error（no-throw 契约）
        assertThat(operationTimerCount(registry, "open", "failure", "io_error")).isEqualTo(1L);
    }

    @Test
    void rejectedRangeIsNotCountedAsStorageFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.start(StorageOperation.OPEN).rejected(StorageRejectionKind.RANGE);

        assertThat(operationTimerCount(registry, "open", "rejected", "range")).isEqualTo(1L);
        assertThat(registry.find(ResourceStorageMeters.OPERATION_DURATION).timers())
                .allMatch(timer -> !"failure".equals(timer.getId().getTag("outcome")));
    }

    @Test
    void terminalStateIsFirstWins() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        StorageMeasurement measurement = meters.start(StorageOperation.SAVE);
        measurement.success(1L);
        measurement.failure(ResourceStorageErrorKind.UNAVAILABLE);
        measurement.rejected(StorageRejectionKind.RANGE);
        measurement.close();

        assertThat(operationTimerCount(registry, "save", "success", "none")).isEqualTo(1L);
        assertThat(registry.find(ResourceStorageMeters.OPERATION_DURATION).timers()).hasSize(1);
        assertThat(registry.get(ResourceStorageMeters.OBJECT_SIZE).summary().count()).isEqualTo(1L);
    }

    @Test
    void closeWithoutTerminalEndsAsIoErrorFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.start(StorageOperation.OPEN).close();

        assertThat(operationTimerCount(registry, "open", "failure", "io_error")).isEqualTo(1L);
    }

    @Test
    void nullRegistryProvidesNoOpBehavior() {
        ResourceStorageMeters meters = new ResourceStorageMeters(null);

        assertThatCode(() -> {
            StorageMeasurement measurement = meters.start(StorageOperation.SAVE);
            measurement.success(1L);
            meters.start(StorageOperation.OPEN).failure(ResourceStorageErrorKind.UNAVAILABLE);
            meters.recordCompensationSuccess();
            meters.recordCompensationFailure(ResourceStorageErrorKind.IO_ERROR);
        }).doesNotThrowAnyException();
    }

    @Test
    void meterRegistrationConflictDoesNotPropagate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // 预注册同名不同类型的 meter，制造 register() 抛 IllegalArgumentException 的场景
        Timer.builder(ResourceStorageMeters.COMPENSATION).register(registry);
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        assertThatCode(() -> meters.recordCompensationSuccess())
                .doesNotThrowAnyException();
        assertThatCode(() -> meters.recordCompensationFailure(ResourceStorageErrorKind.IO_ERROR))
                .doesNotThrowAnyException();
    }

    @Test
    void compensationCountersAreTaggedByOutcomeAndErrorKind() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResourceStorageMeters meters = new ResourceStorageMeters(registry);

        meters.recordCompensationSuccess();
        meters.recordCompensationSuccess();
        meters.recordCompensationFailure(ResourceStorageErrorKind.UNAVAILABLE);
        meters.recordCompensationFailure(null);

        assertThat(compensationCount(registry, "success", "none")).isEqualTo(2.0d);
        assertThat(compensationCount(registry, "failure", "unavailable")).isEqualTo(1.0d);
        // null kind 安全落到 io_error（no-throw 契约）
        assertThat(compensationCount(registry, "failure", "io_error")).isEqualTo(1.0d);
    }

    private static long operationTimerCount(
            SimpleMeterRegistry registry, String operation, String outcome, String errorKind) {
        return registry.get(ResourceStorageMeters.OPERATION_DURATION)
                .tag("operation", operation)
                .tag("outcome", outcome)
                .tag("error.kind", errorKind)
                .timer().count();
    }

    private static double compensationCount(
            SimpleMeterRegistry registry, String outcome, String errorKind) {
        return registry.get(ResourceStorageMeters.COMPENSATION)
                .tag("outcome", outcome)
                .tag("error.kind", errorKind)
                .counter().count();
    }
}

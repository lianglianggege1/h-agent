package com.h.backend.chat;

import com.h.backend.chat.infrastructure.config.ChatStreamProperties;
import com.h.backend.chat.application.ChatStreamConcurrencyGuard;
import com.h.backend.chat.application.impl.RedisChatStreamConcurrencyGuard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamConcurrencyGuardTest {

    @Test
    void shouldDefaultPermitTtlToShortCrashRecoveryWindow() {
        ChatStreamProperties properties = new ChatStreamProperties();

        assertEquals(Duration.ofMinutes(10), properties.getPermitTtl());
    }

    @Test
    void shouldRejectSecondRunForSameSession() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(2, 100, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前 Agent 正在处理中", second.message());
        first.release();
    }

    @Test
    void shouldRejectWhenUserLimitExceeded() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(1, 100, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldAllowDifferentSessionsForSameUserWithinUserLimit() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(2, 100, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);

        assertTrue(first.acquired());
        assertTrue(second.acquired());
        first.release();
        second.release();
    }

    @Test
    void shouldRejectDifferentSessionForSameUserOnlyAfterUnreleasedPermitsReachUserLimit() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(2, 100, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);
        ChatStreamConcurrencyGuard.Permit third = guard.tryAcquire("session-3", 1L);

        assertTrue(first.acquired());
        assertTrue(second.acquired());
        assertFalse(third.acquired());
        assertEquals("当前系统繁忙，请稍后再试", third.message());

        first.release();
        ChatStreamConcurrencyGuard.Permit afterRelease = guard.tryAcquire("session-3", 1L);

        assertTrue(afterRelease.acquired());
        second.release();
        afterRelease.release();
    }

    @Test
    void shouldRejectWhenGlobalLimitExceeded() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(10, 1, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 2L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldAcquireAgainAfterRelease() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = newGuard(1, 1, semaphores);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        first.release();
        first.release();

        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertTrue(second.acquired());
        second.release();
    }

    @Test
    void shouldReleaseAlreadyAcquiredPermitsWhenLaterAcquireFails() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        semaphores.fakeSemaphore("chat:stream:v2:{concurrency}:user:1")
                .failNextAcquire(new IllegalStateException("redis unavailable"));
        RedisChatStreamConcurrencyGuard guard = newGuard(2, 100, semaphores);

        try {
            guard.tryAcquire("session-1", 1L);
        } catch (IllegalStateException ignored) {
        }

        ChatStreamConcurrencyGuard.Permit afterFailure = guard.tryAcquire("session-1", 1L);

        assertTrue(afterFailure.acquired());
        afterFailure.release();
    }

    @Test
    void shouldUseConfiguredPermitTtlWhenAcquiring() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        RedisChatStreamConcurrencyGuard guard = new RedisChatStreamConcurrencyGuard(
                2,
                100,
                Duration.ofMinutes(3),
                semaphores,
                new ManualScheduledExecutorService()
        );

        ChatStreamConcurrencyGuard.Permit permit = guard.tryAcquire("session-1", 1L);

        assertTrue(permit.acquired());
        assertEquals(180_000L, semaphores.fakeSemaphore("chat:stream:v2:{concurrency}:session:session-1")
                .lastAcquireLeaseMillis);
        permit.release();
    }

    @Test
    void shouldRenewOwnedPermitsUntilReleased() {
        FakeSemaphoreClient semaphores = new FakeSemaphoreClient();
        ManualScheduledExecutorService scheduler = new ManualScheduledExecutorService();
        RedisChatStreamConcurrencyGuard guard = new RedisChatStreamConcurrencyGuard(
                2,
                100,
                Duration.ofMinutes(3),
                semaphores,
                scheduler
        );

        ChatStreamConcurrencyGuard.Permit permit = guard.tryAcquire("session-1", 1L);
        scheduler.runPeriodicTask();

        FakeSemaphore sessionSemaphore = semaphores.fakeSemaphore("chat:stream:v2:{concurrency}:session:session-1");
        FakeSemaphore userSemaphore = semaphores.fakeSemaphore("chat:stream:v2:{concurrency}:user:1");
        FakeSemaphore globalSemaphore = semaphores.fakeSemaphore("chat:stream:v2:{concurrency}:global");
        assertEquals(1, sessionSemaphore.renewCalls);
        assertEquals(1, userSemaphore.renewCalls);
        assertEquals(1, globalSemaphore.renewCalls);

        permit.release();
        scheduler.runPeriodicTask();

        assertTrue(scheduler.scheduledFuture.cancelled());
        assertEquals(1, sessionSemaphore.renewCalls);
        assertEquals(1, userSemaphore.renewCalls);
        assertEquals(1, globalSemaphore.renewCalls);
    }

    private RedisChatStreamConcurrencyGuard newGuard(
            int maxConcurrentPerUser,
            int maxConcurrentGlobal,
            FakeSemaphoreClient semaphores
    ) {
        return new RedisChatStreamConcurrencyGuard(
                maxConcurrentPerUser,
                maxConcurrentGlobal,
                Duration.ofMinutes(10),
                semaphores,
                new ManualScheduledExecutorService()
        );
    }

    private static final class FakeSemaphoreClient implements RedisChatStreamConcurrencyGuard.ExpirableSemaphoreClient {

        private final Map<String, FakeSemaphore> semaphores = new HashMap<>();

        @Override
        public RedisChatStreamConcurrencyGuard.ExpirableSemaphore semaphore(String name) {
            return fakeSemaphore(name);
        }

        private FakeSemaphore fakeSemaphore(String name) {
            return semaphores.computeIfAbsent(name, key -> new FakeSemaphore());
        }
    }

    private static final class FakeSemaphore implements RedisChatStreamConcurrencyGuard.ExpirableSemaphore {

        private int permits;
        private int nextPermitId;
        private final Set<String> acquiredPermitIds = new HashSet<>();
        private long lastAcquireLeaseMillis;
        private int renewCalls;
        private RuntimeException nextAcquireFailure;

        private void failNextAcquire(RuntimeException failure) {
            this.nextAcquireFailure = failure;
        }

        @Override
        public void trySetPermits(int permits) {
            if (this.permits == 0) {
                this.permits = permits;
            }
        }

        @Override
        public String tryAcquire(long leaseTime, TimeUnit unit) {
            if (nextAcquireFailure != null) {
                RuntimeException failure = nextAcquireFailure;
                nextAcquireFailure = null;
                throw failure;
            }
            lastAcquireLeaseMillis = unit.toMillis(leaseTime);
            if (acquiredPermitIds.size() >= permits) {
                return null;
            }
            String permitId = "permit-" + nextPermitId++;
            acquiredPermitIds.add(permitId);
            return permitId;
        }

        @Override
        public boolean updateLeaseTime(String permitId, long leaseTime, TimeUnit unit) {
            if (!acquiredPermitIds.contains(permitId)) {
                return false;
            }
            renewCalls++;
            return true;
        }

        @Override
        public boolean tryRelease(String permitId) {
            return acquiredPermitIds.remove(permitId);
        }
    }

    private static final class ManualScheduledExecutorService
            extends AbstractExecutorService implements ScheduledExecutorService {

        private Runnable periodicTask;
        private ManualScheduledFuture scheduledFuture;

        void runPeriodicTask() {
            if (periodicTask != null && !scheduledFuture.cancelled()) {
                periodicTask.run();
            }
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command,
                long initialDelay,
                long period,
                TimeUnit unit
        ) {
            this.periodicTask = command;
            this.scheduledFuture = new ManualScheduledFuture();
            return scheduledFuture;
        }

        @Override
        public void shutdown() {
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return java.util.List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ManualScheduledFuture implements RunnableScheduledFuture<Object> {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        boolean cancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isPeriodic() {
            return true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public void run() {
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}

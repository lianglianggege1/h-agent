package com.h.backend.chat.service.impl;

import com.h.backend.chat.config.ChatStreamProperties;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class RedisChatStreamConcurrencyGuard implements ChatStreamConcurrencyGuard {

    private static final String SESSION_BUSY_MESSAGE = "当前会话正在处理中";
    private static final String SYSTEM_BUSY_MESSAGE = "当前系统繁忙，请稍后再试";

    private final int maxConcurrentPerUser;
    private final int maxConcurrentGlobal;
    private final long permitTtlMillis;
    private final long renewalIntervalMillis;
    private final ExpirableSemaphoreClient semaphoreClient;
    private final ScheduledExecutorService renewalExecutor;

    @Autowired
    public RedisChatStreamConcurrencyGuard(
            ChatStreamProperties properties,
            RedissonClient redissonClient,
            @Value("${spring.application.name}") String applicationName
    ) {
        this(
                properties.getMaxConcurrentPerUser(),
                properties.getMaxConcurrentGlobal(),
                properties.getPermitTtl(),
                new RedissonExpirableSemaphoreClient(redissonClient, applicationName),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "chat-stream-permit-renewal");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    public RedisChatStreamConcurrencyGuard(
            int maxConcurrentPerUser,
            int maxConcurrentGlobal,
            Duration permitTtl,
            ExpirableSemaphoreClient semaphoreClient,
            ScheduledExecutorService renewalExecutor
    ) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxConcurrentGlobal = maxConcurrentGlobal;
        this.permitTtlMillis = Math.max(1L, permitTtl.toMillis());
        this.renewalIntervalMillis = Math.max(1L, this.permitTtlMillis / 3L);
        this.semaphoreClient = semaphoreClient;
        this.renewalExecutor = renewalExecutor;
    }

    @Override
    public Permit tryAcquire(String sessionId, Long userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        HeldPermit sessionPermit = null;
        HeldPermit userPermit = null;
        HeldPermit globalPermit = null;
        try {
            ExpirableSemaphore sessionSemaphore = semaphoreClient.semaphore(sessionKey(sessionId));
            sessionSemaphore.trySetPermits(1);
            String sessionPermitId = sessionSemaphore.tryAcquire(permitTtlMillis, TimeUnit.MILLISECONDS);
            if (sessionPermitId == null) {
                return rejected(SESSION_BUSY_MESSAGE);
            }
            sessionPermit = new HeldPermit(sessionSemaphore, sessionPermitId);

            ExpirableSemaphore userSemaphore = semaphoreClient.semaphore(userKey(userId));
            userSemaphore.trySetPermits(maxConcurrentPerUser);
            String userPermitId = userSemaphore.tryAcquire(permitTtlMillis, TimeUnit.MILLISECONDS);
            if (userPermitId == null) {
                sessionPermit.release();
                sessionPermit = null;
                return rejected(SYSTEM_BUSY_MESSAGE);
            }
            userPermit = new HeldPermit(userSemaphore, userPermitId);

            ExpirableSemaphore globalSemaphore = semaphoreClient.semaphore(globalKey());
            globalSemaphore.trySetPermits(maxConcurrentGlobal);
            String globalPermitId = globalSemaphore.tryAcquire(permitTtlMillis, TimeUnit.MILLISECONDS);
            if (globalPermitId == null) {
                userPermit.release();
                userPermit = null;
                sessionPermit.release();
                sessionPermit = null;
                return rejected(SYSTEM_BUSY_MESSAGE);
            }
            globalPermit = new HeldPermit(globalSemaphore, globalPermitId);

            return new AcquiredPermit(sessionPermit, userPermit, globalPermit);
        } catch (RuntimeException ex) {
            if (globalPermit != null) {
                globalPermit.release();
            }
            if (userPermit != null) {
                userPermit.release();
            }
            if (sessionPermit != null) {
                sessionPermit.release();
            }
            throw ex;
        }
    }

    private String sessionKey(String sessionId) {
        return "chat:stream:{concurrency}:session:" + sessionId;
    }

    private String userKey(Long userId) {
        return "chat:stream:{concurrency}:user:" + userId;
    }

    private String globalKey() {
        return "chat:stream:{concurrency}:global";
    }

    private Permit rejected(String message) {
        return new RejectedPermit(message);
    }

    public interface ExpirableSemaphoreClient {
        ExpirableSemaphore semaphore(String name);
    }

    public interface ExpirableSemaphore {
        void trySetPermits(int permits);

        String tryAcquire(long leaseTime, TimeUnit unit);

        boolean updateLeaseTime(String permitId, long leaseTime, TimeUnit unit);

        boolean tryRelease(String permitId);
    }

    private final class AcquiredPermit implements Permit {

        private final HeldPermit sessionPermit;
        private final HeldPermit userPermit;
        private final HeldPermit globalPermit;
        private final AtomicBoolean released = new AtomicBoolean();
        private final ScheduledFuture<?> renewalTask;

        private AcquiredPermit(HeldPermit sessionPermit, HeldPermit userPermit, HeldPermit globalPermit) {
            this.sessionPermit = sessionPermit;
            this.userPermit = userPermit;
            this.globalPermit = globalPermit;
            this.renewalTask = renewalExecutor.scheduleAtFixedRate(
                    this::renew,
                    renewalIntervalMillis,
                    renewalIntervalMillis,
                    TimeUnit.MILLISECONDS
            );
        }

        @Override
        public boolean acquired() {
            return true;
        }

        @Override
        public String message() {
            return "";
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                renewalTask.cancel(false);
                globalPermit.release();
                userPermit.release();
                sessionPermit.release();
            }
        }

        private void renew() {
            if (released.get()) {
                return;
            }
            try {
                boolean renewed = sessionPermit.renew(permitTtlMillis)
                        && userPermit.renew(permitTtlMillis)
                        && globalPermit.renew(permitTtlMillis);
                if (!renewed) {
                    release();
                }
            } catch (RuntimeException ex) {
                log.warn("Failed to renew chat stream concurrency permit", ex);
            }
        }
    }

    private record HeldPermit(ExpirableSemaphore semaphore, String permitId) {

        private boolean renew(long permitTtlMillis) {
            return semaphore.updateLeaseTime(permitId, permitTtlMillis, TimeUnit.MILLISECONDS);
        }

        private void release() {
            semaphore.tryRelease(permitId);
        }
    }

    private record RejectedPermit(String message) implements Permit {

        @Override
        public boolean acquired() {
            return false;
        }

        @Override
        public void release() {
        }
    }

    private static final class RedissonExpirableSemaphoreClient implements ExpirableSemaphoreClient {

        private final RedissonClient redissonClient;
        private final String applicationName;

        private RedissonExpirableSemaphoreClient(RedissonClient redissonClient, String applicationName) {
            this.redissonClient = redissonClient;
            this.applicationName = applicationName;
        }

        @Override
        public ExpirableSemaphore semaphore(String name) {
            return new RedissonExpirableSemaphore(redissonClient.getPermitExpirableSemaphore(buildKey(name)));
        }

        private String buildKey(String key) {
            return applicationName + "_" + key;
        }
    }

    private record RedissonExpirableSemaphore(RPermitExpirableSemaphore semaphore) implements ExpirableSemaphore {

        @Override
        public void trySetPermits(int permits) {
            semaphore.trySetPermits(permits);
        }

        @Override
        public String tryAcquire(long leaseTime, TimeUnit unit) {
            try {
                return semaphore.tryAcquire(leaseTime, unit);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring chat stream concurrency permit", ex);
            }
        }

        @Override
        public boolean updateLeaseTime(String permitId, long leaseTime, TimeUnit unit) {
            return semaphore.updateLeaseTime(permitId, leaseTime, unit);
        }

        @Override
        public boolean tryRelease(String permitId) {
            return semaphore.tryRelease(permitId);
        }
    }
}

package com.h.backend.chat;

import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import com.h.backend.chat.service.impl.RedisChatStreamConcurrencyGuard;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamConcurrencyGuardTest {

    @Test
    void shouldRejectSecondRunForSameSession() {
        RedisChatStreamConcurrencyGuard guard = newGuard(2, 100);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前会话正在处理中", second.message());
        first.release();
    }

    @Test
    void shouldRejectWhenUserLimitExceeded() {
        RedisChatStreamConcurrencyGuard guard = newGuard(1, 100);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 1L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldRejectWhenGlobalLimitExceeded() {
        RedisChatStreamConcurrencyGuard guard = newGuard(10, 1);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-2", 2L);

        assertTrue(first.acquired());
        assertFalse(second.acquired());
        assertEquals("当前系统繁忙，请稍后再试", second.message());
        first.release();
    }

    @Test
    void shouldAcquireAgainAfterRelease() {
        RedisChatStreamConcurrencyGuard guard = newGuard(1, 1);

        ChatStreamConcurrencyGuard.Permit first = guard.tryAcquire("session-1", 1L);
        first.release();
        first.release();

        ChatStreamConcurrencyGuard.Permit second = guard.tryAcquire("session-1", 1L);

        assertTrue(first.acquired());
        assertTrue(second.acquired());
        second.release();
    }

    @Test
    void shouldUseConfiguredPermitTtlWhenAcquiring() {
        FakeRedisScriptRunner runner = new FakeRedisScriptRunner();
        RedisChatStreamConcurrencyGuard guard = new RedisChatStreamConcurrencyGuard(
                2,
                100,
                Duration.ofMinutes(3),
                runner
        );

        ChatStreamConcurrencyGuard.Permit permit = guard.tryAcquire("session-1", 1L);

        assertTrue(permit.acquired());
        assertEquals(180_000L, runner.lastAcquireTtlMillis);
        permit.release();
    }

    private RedisChatStreamConcurrencyGuard newGuard(int maxConcurrentPerUser, int maxConcurrentGlobal) {
        return new RedisChatStreamConcurrencyGuard(
                maxConcurrentPerUser,
                maxConcurrentGlobal,
                Duration.ofMinutes(10),
                new FakeRedisScriptRunner()
        );
    }

    private static final class FakeRedisScriptRunner implements RedisChatStreamConcurrencyGuard.RedisScriptRunner {

        private final Set<String> activeSessions = new HashSet<>();
        private final Map<String, Integer> activeUsers = new ConcurrentHashMap<>();
        private int activeGlobal;
        private long lastAcquireTtlMillis;

        @Override
        public Long runAcquire(List<String> keys, int maxConcurrentPerUser, int maxConcurrentGlobal, long ttlMillis) {
            String sessionKey = keys.get(0);
            String userKey = keys.get(1);
            lastAcquireTtlMillis = ttlMillis;

            if (activeSessions.contains(sessionKey)) {
                return 1L;
            }
            if (activeUsers.getOrDefault(userKey, 0) >= maxConcurrentPerUser) {
                return 2L;
            }
            if (activeGlobal >= maxConcurrentGlobal) {
                return 3L;
            }

            activeSessions.add(sessionKey);
            activeUsers.merge(userKey, 1, Integer::sum);
            activeGlobal++;
            return 0L;
        }

        @Override
        public Long runRelease(List<String> keys) {
            String sessionKey = keys.get(0);
            String userKey = keys.get(1);

            if (!activeSessions.remove(sessionKey)) {
                return 0L;
            }

            activeUsers.computeIfPresent(userKey, (key, count) -> count > 1 ? count - 1 : null);
            if (activeGlobal > 0) {
                activeGlobal--;
            }
            return 1L;
        }
    }
}

package com.h.backend.chat.service.impl;

import com.h.backend.chat.config.ChatStreamProperties;
import com.h.backend.chat.service.ChatStreamConcurrencyGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisChatStreamConcurrencyGuard implements ChatStreamConcurrencyGuard {

    private static final String SESSION_BUSY_MESSAGE = "当前会话正在处理中";
    private static final String SYSTEM_BUSY_MESSAGE = "当前系统繁忙，请稍后再试";
    private static final Long ACQUIRED = 0L;
    private static final Long SESSION_BUSY = 1L;
    private static final String ACQUIRE_SCRIPT = """
            local sessionKey = KEYS[1]
            local userKey = KEYS[2]
            local globalKey = KEYS[3]
            local sessionId = ARGV[1]
            local maxUser = tonumber(ARGV[2])
            local maxGlobal = tonumber(ARGV[3])
            local ttlMillis = tonumber(ARGV[4])
            local nowMillis = tonumber(ARGV[5])
            local expiresAt = nowMillis + ttlMillis

            redis.call('ZREMRANGEBYSCORE', userKey, '-inf', nowMillis)
            redis.call('ZREMRANGEBYSCORE', globalKey, '-inf', nowMillis)

            if redis.call('EXISTS', sessionKey) == 1 then
                return 1
            end
            if redis.call('ZCARD', userKey) >= maxUser then
                return 2
            end
            if redis.call('ZCARD', globalKey) >= maxGlobal then
                return 3
            end

            redis.call('SET', sessionKey, sessionId, 'PX', ttlMillis)
            redis.call('ZADD', userKey, expiresAt, sessionId)
            redis.call('ZADD', globalKey, expiresAt, sessionId)
            redis.call('PEXPIRE', userKey, ttlMillis)
            redis.call('PEXPIRE', globalKey, ttlMillis)
            return 0
            """;
    private static final String RELEASE_SCRIPT = """
            local sessionKey = KEYS[1]
            local userKey = KEYS[2]
            local globalKey = KEYS[3]
            local sessionId = ARGV[1]

            if redis.call('DEL', sessionKey) == 0 then
                return 0
            end

            redis.call('ZREM', userKey, sessionId)
            redis.call('ZREM', globalKey, sessionId)
            return 1
            """;

    private final int maxConcurrentPerUser;
    private final int maxConcurrentGlobal;
    private final long permitTtlMillis;
    private final RedisScriptRunner redisScriptRunner;

    @Autowired
    public RedisChatStreamConcurrencyGuard(
            ChatStreamProperties properties,
            StringRedisTemplate stringRedisTemplate,
            @Value("${spring.application.name}") String applicationName
    ) {
        this(
                properties.getMaxConcurrentPerUser(),
                properties.getMaxConcurrentGlobal(),
                properties.getPermitTtl(),
                new StringRedisScriptRunner(stringRedisTemplate, applicationName)
        );
    }

    public RedisChatStreamConcurrencyGuard(
            int maxConcurrentPerUser,
            int maxConcurrentGlobal,
            Duration permitTtl,
            RedisScriptRunner redisScriptRunner
    ) {
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxConcurrentGlobal = maxConcurrentGlobal;
        this.permitTtlMillis = Math.max(1L, permitTtl.toMillis());
        this.redisScriptRunner = redisScriptRunner;
    }

    @Override
    public Permit tryAcquire(String sessionId, Long userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");

        List<String> keys = keys(sessionId, userId);
        Long result = redisScriptRunner.runAcquire(
                keys,
                maxConcurrentPerUser,
                maxConcurrentGlobal,
                permitTtlMillis
        );

        if (ACQUIRED.equals(result)) {
            return new AcquiredPermit(sessionId, userId, keys);
        }
        if (SESSION_BUSY.equals(result)) {
            return rejected(SESSION_BUSY_MESSAGE);
        }
        return rejected(SYSTEM_BUSY_MESSAGE);
    }

    private List<String> keys(String sessionId, Long userId) {
        return List.of(
                "chat:stream:{concurrency}:session:" + sessionId,
                "chat:stream:{concurrency}:user:" + userId,
                "chat:stream:{concurrency}:global"
        );
    }

    private Permit rejected(String message) {
        return new RejectedPermit(message);
    }

    public interface RedisScriptRunner {
        Long runAcquire(List<String> keys, int maxConcurrentPerUser, int maxConcurrentGlobal, long ttlMillis);

        Long runRelease(List<String> keys);
    }

    private final class AcquiredPermit implements Permit {

        private final String sessionId;
        private final Long userId;
        private final List<String> keys;
        private final AtomicBoolean released = new AtomicBoolean();

        private AcquiredPermit(String sessionId, Long userId, List<String> keys) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.keys = keys;
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
                redisScriptRunner.runRelease(keys);
            }
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

    private static final class StringRedisScriptRunner implements RedisScriptRunner {

        private final StringRedisTemplate stringRedisTemplate;
        private final String applicationName;

        private StringRedisScriptRunner(StringRedisTemplate stringRedisTemplate, String applicationName) {
            this.stringRedisTemplate = stringRedisTemplate;
            this.applicationName = applicationName;
        }

        @Override
        public Long runAcquire(List<String> keys, int maxConcurrentPerUser, int maxConcurrentGlobal, long ttlMillis) {
            return stringRedisTemplate.execute(
                    redisScript(ACQUIRE_SCRIPT),
                    keys.stream().map(this::buildKey).toList(),
                    keys.get(0),
                    String.valueOf(maxConcurrentPerUser),
                    String.valueOf(maxConcurrentGlobal),
                    String.valueOf(ttlMillis),
                    String.valueOf(System.currentTimeMillis())
            );
        }

        @Override
        public Long runRelease(List<String> keys) {
            return stringRedisTemplate.execute(
                    redisScript(RELEASE_SCRIPT),
                    keys.stream().map(this::buildKey).toList(),
                    keys.get(0)
            );
        }

        private String buildKey(String key) {
            return applicationName + "_" + key;
        }

        private DefaultRedisScript<Long> redisScript(String script) {
            DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
            redisScript.setScriptText(script);
            redisScript.setResultType(Long.class);
            return redisScript;
        }
    }
}

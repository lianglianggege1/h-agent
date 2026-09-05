package com.h.backend.captcha.infrastructure.redis;

import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.captcha.infrastructure.config.HumanVerificationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Redis 固定窗口计数限流。key 形如 {prefix}:rate:{operation}:{dimensionHash}:{window}，
 * 不包含明文邮箱或完整 IP。
 */
@Slf4j
public class RedisCaptchaRateLimiter implements CaptchaRateLimiter {

    private static final RedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
                    "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
                    "if current > tonumber(ARGV[2]) then return 0 end " +
                    "return 1", Long.class);

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final HumanVerificationProperties.RateLimit config;
    private final String keyPrefix;
    private final MeterRegistry meterRegistry;

    public RedisCaptchaRateLimiter(StringRedisTemplate redisTemplate,
                                   HumanVerificationProperties properties,
                                   MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.config = properties.getRateLimit();
        this.keyPrefix = properties.getKeyPrefix() + ":rate";
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void checkIssueAllowed(ClientContext client) {
        check("issue", "ip:" + dimensionHash(client.clientIp()), config.getIssuePerMinutePerIp(), MINUTE, "issue");
    }

    @Override
    public void checkSolveAllowed(ClientContext client, String subjectFingerprint) {
        check("solve-ip", "ip:" + dimensionHash(client.clientIp()), config.getSolvePerMinutePerIp(), MINUTE, "solve");
        check("solve-subject", "subject:" + dimensionHash(subjectFingerprint),
                config.getSolvePerMinutePerSubject(), MINUTE, "solve");
    }

    @Override
    public void checkAuthAllowed(CaptchaPurpose purpose, String subjectFingerprint) {
        String operation = purpose.name().toLowerCase(java.util.Locale.ROOT);
        check("auth-subject:" + operation, "subject:" + dimensionHash(subjectFingerprint),
                config.getAuthSubjectPer10Minutes(), TEN_MINUTES, operation);
    }

    @Override
    public void checkAuthIpAllowed(CaptchaPurpose purpose, ClientContext client) {
        String operation = purpose.name().toLowerCase(java.util.Locale.ROOT);
        check("auth-ip:" + operation, "ip:" + dimensionHash(client.clientIp()),
                config.getAuthIpPer10Minutes(), TEN_MINUTES, operation);
    }

    private void check(String operation, String dimension, int limit, Duration window, String metricOperation) {
        long windowSeconds = window.toSeconds();
        long windowStart = System.currentTimeMillis() / 1000 / windowSeconds * windowSeconds;
        String key = keyPrefix + ":" + operation + ":" + dimension + ":" + windowStart;
        try {
            Long allowed = redisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(key),
                    String.valueOf(windowSeconds + 1), String.valueOf(limit));
            if (allowed == null || allowed != 1L) {
                meterRegistry.counter("captcha_rate_limited_total", "operation", metricOperation).increment();
                log.warn("[Captcha] 限流触发 operation={} dimensionType={}", operation,
                        dimension.startsWith("ip:") ? "ip" : "subject");
                throw new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED);
            }
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            // 限流依赖 Redis，故障时 fail closed
            log.error("[Captcha] 限流 Redis 异常 operation={} type={}", operation,
                    e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        }
    }

    private static String dimensionHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

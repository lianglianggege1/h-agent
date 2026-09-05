package com.h.backend.captcha.infrastructure.redis;

import com.h.backend.captcha.application.CaptchaStateStore;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * challenge metadata / proof 的 Redis 实现。
 * 消费使用 Lua 原子 GET+DEL；proof 写入使用 SET NX EX；任一 Redis 异常 fail closed。
 */
@Slf4j
public class RedisCaptchaStateStore implements CaptchaStateStore {

    private static final RedisScript<String> GET_DEL_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) " +
                    "if v == false then return nil end " +
                    "redis.call('DEL', KEYS[1]) " +
                    "return v", String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisCaptchaStateStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void saveChallengeMetadata(String challengeId, CaptchaPurpose purpose, String subjectFingerprint,
                                      Duration ttl) {
        try {
            redisTemplate.opsForValue().set(challengeKey(challengeId),
                    toJson(new VerificationBindingJson(purpose.name(), subjectFingerprint, Instant.now())), ttl);
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("saveChallengeMetadata", e);
        }
    }

    @Override
    public Optional<VerificationBinding> consumeChallengeMetadata(String challengeId) {
        return executeGetDel(challengeKey(challengeId));
    }

    @Override
    public boolean saveProofIfAbsent(String proofHash, CaptchaPurpose purpose, String subjectFingerprint,
                                      Duration ttl) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(proofKey(proofHash),
                    toJson(new VerificationBindingJson(purpose.name(), subjectFingerprint, Instant.now())), ttl);
            return Boolean.TRUE.equals(ok);
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("saveProofIfAbsent", e);
        }
    }

    @Override
    public Optional<VerificationBinding> consumeProof(String proofHash) {
        return executeGetDel(proofKey(proofHash));
    }

    private Optional<VerificationBinding> executeGetDel(String key) {
        try {
            String json = redisTemplate.execute(GET_DEL_SCRIPT, List.of(key));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            VerificationBindingJson parsed = objectMapper.readValue(json, VerificationBindingJson.class);
            return Optional.of(new VerificationBinding(
                    CaptchaPurpose.valueOf(parsed.purpose()),
                    parsed.subjectFingerprint(),
                    parsed.issuedAt()));
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("executeGetDel", e);
        }
    }

    private String challengeKey(String challengeId) {
        return keyPrefix + ":challenge:" + challengeId;
    }

    private String proofKey(String proofHash) {
        return keyPrefix + ":proof:" + proofHash;
    }

    private String toJson(VerificationBindingJson binding) {
        return objectMapper.writeValueAsString(binding);
    }

    private CaptchaException unavailable(String operation, Exception cause) {
        log.error("[Captcha] Redis 状态存储异常 operation={} type={}", operation, cause.getClass().getSimpleName(),
                cause);
        return new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, cause);
    }

    /**
     * 设计 §6.2/§6.3 的存储 JSON 结构。
     */
    record VerificationBindingJson(String purpose, String subjectFingerprint, Instant issuedAt) {
    }
}

package com.h.backend.captcha;

import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.captcha.infrastructure.config.HumanVerificationProperties;
import com.h.backend.captcha.infrastructure.redis.RedisCaptchaRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 设计 §10/§13.9 限流器测试：固定窗口计数、独立 prefix 隔离、Redis 故障 fail closed。
 * 使用独立 key prefix 和小阈值，避免污染其他测试使用的限流窗口。
 */
@SpringBootTest
class RedisCaptchaRateLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void issueRateLimitShouldRejectAfterThreshold() {
        CaptchaRateLimiter limiter = isolatedLimiter(3, 3, 3, 3, 3);
        ClientContext client = ClientContext.of("10.9.8.7");

        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> limiter.checkIssueAllowed(client));
        }
        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> limiter.checkIssueAllowed(client));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, exception.getKind());
        assertEquals(CaptchaErrors.MSG_RATE_LIMITED, exception.getMessage());
    }

    @Test
    void solveRateLimitShouldCountBothIpAndSubjectDimensions() {
        CaptchaRateLimiter limiter = isolatedLimiter(3, 10, 2, 3, 3);
        ClientContext client = ClientContext.of("10.9.8.8");

        limiter.checkSolveAllowed(client, "hmac-sha256:subject-a");
        limiter.checkSolveAllowed(client, "hmac-sha256:subject-a");
        // subject-a 第 3 次达到上限（固定窗口计数超过 2）
        CaptchaException subjectLimited = assertThrows(CaptchaException.class,
                () -> limiter.checkSolveAllowed(client, "hmac-sha256:subject-a"));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, subjectLimited.getKind());
        // subject-b 是独立维度，不受 subject-a 计数影响
        assertDoesNotThrow(() -> limiter.checkSolveAllowed(client, "hmac-sha256:subject-b"));
    }

    @Test
    void authRateLimitShouldSeparateLoginAndRegisterPurposes() {
        CaptchaRateLimiter limiter = isolatedLimiter(3, 3, 3, 3, 2);
        ClientContext client = ClientContext.of("10.9.8.9");

        limiter.checkAuthAllowed(CaptchaPurpose.LOGIN, "hmac-sha256:auth-subject");
        limiter.checkAuthIpAllowed(CaptchaPurpose.LOGIN, client);

        limiter.checkAuthAllowed(CaptchaPurpose.LOGIN, "hmac-sha256:auth-subject");
        limiter.checkAuthIpAllowed(CaptchaPurpose.REGISTER, ClientContext.of("10.9.8.9"));

        CaptchaException loginLimited = assertThrows(CaptchaException.class,
                () -> limiter.checkAuthAllowed(CaptchaPurpose.LOGIN, "hmac-sha256:auth-subject"));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, loginLimited.getKind());
        // REGISTER 是独立维度，不受 LOGIN 计数影响
        assertDoesNotThrow(() -> limiter.checkAuthAllowed(CaptchaPurpose.REGISTER, "hmac-sha256:auth-subject"));
    }

    @Test
    void redisFailureShouldFailClosed() {
        StringRedisTemplate broken = new StringRedisTemplate() {
            @Override
            public <T> T execute(org.springframework.data.redis.core.script.RedisScript<T> script,
                                List<String> keys, Object... args) {
                throw new IllegalStateException("redis down");
            }
        };
        broken.setConnectionFactory(redisTemplate.getConnectionFactory());
        broken.afterPropertiesSet();

        HumanVerificationProperties properties = new HumanVerificationProperties();
        properties.setKeyPrefix("h-agent:test:rate:" + System.nanoTime());
        CaptchaRateLimiter limiter = new RedisCaptchaRateLimiter(broken, properties, new SimpleMeterRegistry());

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> limiter.checkIssueAllowed(ClientContext.of("10.9.8.10")));
        assertEquals(CaptchaException.Kind.UNAVAILABLE, exception.getKind());
    }

    private CaptchaRateLimiter isolatedLimiter(int issue, int solveIp, int solveSubject, int authIp, int authSubject) {
        HumanVerificationProperties properties = new HumanVerificationProperties();
        properties.setKeyPrefix("h-agent:test:rate:" + System.nanoTime());
        properties.getRateLimit().setIssuePerMinutePerIp(issue);
        properties.getRateLimit().setSolvePerMinutePerIp(solveIp);
        properties.getRateLimit().setSolvePerMinutePerSubject(solveSubject);
        properties.getRateLimit().setAuthIpPer10Minutes(authIp);
        properties.getRateLimit().setAuthSubjectPer10Minutes(authSubject);
        return new RedisCaptchaRateLimiter(redisTemplate, properties, new SimpleMeterRegistry());
    }
}

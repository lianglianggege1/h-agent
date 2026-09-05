package com.h.backend.captcha;

import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.application.CaptchaStateStore;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.CaptchaTrack;
import com.h.backend.captcha.domain.ClientContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 真实 Redis + 真实 HumanVerificationImpl 的集成测试（设计 §13.2/§13.3）：
 * 验证 GETDEL 原子性、SET NX EX 一次性、TTL 生效与并发消费不变量。
 * CaptchaEngine 用固定成功 fake（真实引擎兼容性由 TianaiCaptchaAdapterIntegrationTest 覆盖）。
 */
@SpringBootTest
class HumanVerificationRedisIntegrationTest {

    private static final ClientContext CLIENT = ClientContext.of("127.0.0.1");

    @Autowired
    private HumanVerification humanVerification;

    @Autowired
    private CaptchaStateStore stateStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private CaptchaEngine captchaEngine;

    @MockitoBean
    private CaptchaRateLimiter captchaRateLimiter;

    @BeforeEach
    void stubEngine() {
        Mockito.when(captchaEngine.generateSlider()).thenAnswer(inv -> new CaptchaChallenge(
                "SLIDER_FAKE_" + System.nanoTime(), "SLIDER", "data:image/jpeg;base64,bg",
                "data:image/png;base64,tpl", 600, 360, 110, 360));
        when(captchaEngine.matching(any(), any())).thenReturn(true);
    }

    @Test
    void fullFlowShouldSurviveEmailNormalizationAcrossStages() {
        String email = "redis_" + System.nanoTime() + "@Example.COM";

        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.LOGIN,
                "  " + email + "  ", CLIENT);
        String proof = humanVerification.solveChallenge(new CaptchaSolution(
                challenge.id(), CaptchaPurpose.LOGIN, email.toLowerCase(), track()), CLIENT).rawProof();

        humanVerification.consumeProof(proof, CaptchaPurpose.LOGIN, email);

        assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(proof, CaptchaPurpose.LOGIN, email));
    }

    @Test
    void challengeMetadataShouldExpireByTtl() throws InterruptedException {
        String challengeId = "ttl_" + System.nanoTime();

        stateStore.saveChallengeMetadata(challengeId, CaptchaPurpose.LOGIN, "hmac-sha256:test", Duration.ofMillis(500));
        Thread.sleep(800);

        assertTrue(stateStore.consumeChallengeMetadata(challengeId).isEmpty(), "TTL 过期后 metadata 不可消费");
    }

    @Test
    void proofShouldExpireByTtl() throws InterruptedException {
        String proofHash = "ttl_" + System.nanoTime();

        assertTrue(stateStore.saveProofIfAbsent(proofHash, CaptchaPurpose.LOGIN, "hmac-sha256:test",
                Duration.ofMillis(500)));
        Thread.sleep(800);

        assertTrue(stateStore.consumeProof(proofHash).isEmpty(), "TTL 过期后 proof 不可消费");
    }

    @Test
    void saveProofIfAbsentShouldNotOverwriteExistingProof() {
        String proofHash = "nx_" + System.nanoTime();

        assertTrue(stateStore.saveProofIfAbsent(proofHash, CaptchaPurpose.LOGIN, "hmac-sha256:test",
                Duration.ofSeconds(60)));
        assertTrue(!stateStore.saveProofIfAbsent(proofHash, CaptchaPurpose.REGISTER, "hmac-sha256:other",
                Duration.ofSeconds(60)), "key 冲突时不覆盖现有 proof");

        CaptchaStateStore.VerificationBinding binding = stateStore.consumeProof(proofHash).orElseThrow();
        assertEquals(CaptchaPurpose.LOGIN, binding.purpose(), "保留首次写入的绑定信息");
        assertEquals("hmac-sha256:test", binding.subjectFingerprint());
    }

    @Test
    void issuedChallengeKeyShouldCarryTtl() {
        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.LOGIN,
                "ttlcheck_" + System.nanoTime() + "@example.com", CLIENT);

        Long ttlSeconds = redisTemplate.getExpire(
                "h-agent:human-verification:challenge:" + challenge.id(), java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(ttlSeconds != null && ttlSeconds > 100 && ttlSeconds <= 120,
                "challenge metadata TTL 应接近 120s，实际=" + ttlSeconds);
    }

    @Test
    void hundredConcurrentConsumersOnRealRedisShouldProduceExactlyOneSuccess() throws Exception {
        String email = "conc_" + System.nanoTime() + "@example.com";
        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.LOGIN, email, CLIENT);
        String proof = humanVerification.solveChallenge(new CaptchaSolution(
                challenge.id(), CaptchaPurpose.LOGIN, email, track()), CLIENT).rawProof();

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger invalid = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    humanVerification.consumeProof(proof, CaptchaPurpose.LOGIN, email);
                    successes.incrementAndGet();
                } catch (CaptchaException e) {
                    if (e.getKind() == com.h.backend.captcha.domain.CaptchaException.Kind.PROOF_INVALID) {
                        invalid.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "真实 Redis 下同一 proof 并发消费恰好一个成功");
        assertEquals(threads - 1, invalid.get(), "其余均应统一为 PROOF_INVALID");
        assertEquals(0, unexpected.get(), "不应出现依赖故障类错误");
    }

    @Test
    void solveTwiceOnRealRedisShouldFailWithExpired() {
        String email = "twice_" + System.nanoTime() + "@example.com";
        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.LOGIN, email, CLIENT);

        humanVerification.solveChallenge(new CaptchaSolution(challenge.id(), CaptchaPurpose.LOGIN, email, track()),
                CLIENT);

        CaptchaException second = assertThrows(CaptchaException.class,
                () -> humanVerification.solveChallenge(new CaptchaSolution(challenge.id(), CaptchaPurpose.LOGIN,
                        email, track()), CLIENT));
        assertEquals(CaptchaException.Kind.EXPIRED, second.getKind());
        assertEquals(CaptchaErrors.MSG_TAC_EXPIRED, second.getMessage());
    }

    private static CaptchaTrack track() {
        return new CaptchaTrack(300, 180, 55, 180, 1000L, 1834L,
                List.of(new CaptchaTrack.TrackPoint(10f, 10f, 0f, "down"),
                        new CaptchaTrack.TrackPoint(120f, 11f, 834f, "up")));
    }
}

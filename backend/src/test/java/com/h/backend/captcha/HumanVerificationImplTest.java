package com.h.backend.captcha;

import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.application.CaptchaStateStore;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.application.impl.HumanVerificationImpl;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.CaptchaTrack;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.captcha.domain.SubjectFingerprintCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设计 §13.2 Human Verification 模块测试：一次性、绑定、fail-closed 不变量。
 */
class HumanVerificationImplTest {

    private static final String EMAIL = "user@example.com";
    private static final ClientContext CLIENT = ClientContext.of("127.0.0.1");

    private FakeEngine engine;
    private FakeStateStore stateStore;
    private FakeRateLimiter rateLimiter;
    private HumanVerification humanVerification;

    @BeforeEach
    void setUp() {
        engine = new FakeEngine();
        stateStore = new FakeStateStore();
        rateLimiter = new FakeRateLimiter();
        humanVerification = new HumanVerificationImpl(engine, stateStore, rateLimiter,
                new SubjectFingerprintCalculator("unit-test-hmac-secret-0123456789abcdef"),
                Duration.ofSeconds(120), Duration.ofSeconds(90), new SimpleMeterRegistry());
    }

    @Test
    void issueChallengeShouldSaveMetadataWithPurposeAndFingerprint() {
        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.LOGIN, EMAIL, CLIENT);

        assertEquals(FakeEngine.CHALLENGE_ID, challenge.id());
        assertEquals("SLIDER", challenge.type());
        CaptchaStateStore.VerificationBinding metadata =
                stateStore.metadata.get(FakeEngine.CHALLENGE_ID);
        assertNotNull(metadata);
        assertEquals(CaptchaPurpose.LOGIN, metadata.purpose());
        assertTrue(metadata.subjectFingerprint().startsWith("hmac-sha256:"));
        assertEquals(Duration.ofSeconds(120), stateStore.lastMetadataTtl);
    }

    @Test
    void issueChallengeShouldFailClosedWhenMetadataSaveFails() {
        stateStore.failOnSaveMetadata = true;

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> humanVerification.issueChallenge(CaptchaPurpose.LOGIN, EMAIL, CLIENT));

        assertEquals(CaptchaException.Kind.UNAVAILABLE, exception.getKind());
    }

    @Test
    void solveChallengeShouldIssueRandomProofAndStoreOnlyHash() {
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);

        byte[] decoded = Base64.getUrlDecoder().decode(proof.rawProof());
        assertTrue(decoded.length >= 32, "proof 至少 256 bit");
        assertEquals(90, proof.expiresInSeconds());
        String proofHash = sha256Hex(proof.rawProof());
        assertNotNull(stateStore.proofs.get(proofHash), "Redis 只保存 proof 的 SHA-256");
        for (String key : stateStore.proofs.keySet()) {
            assertNotEquals(proof.rawProof(), key, "raw proof 不能作为存储 key");
        }
    }

    @Test
    void twoSolvesShouldIssueDifferentProofs() {
        CaptchaProofHolder first = solve(EMAIL, CaptchaPurpose.LOGIN);
        engine.reset();
        CaptchaProofHolder second = solve(EMAIL, CaptchaPurpose.LOGIN);

        assertNotEquals(first.rawProof(), second.rawProof());
    }

    @Test
    void solveChallengeShouldNotIssueProofWhenMatchingFails() {
        issue(EMAIL, CaptchaPurpose.LOGIN);
        engine.matchResult = false;

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN));

        assertEquals(CaptchaException.Kind.MISMATCH, exception.getKind());
        assertTrue(stateStore.proofs.isEmpty(), "匹配失败不签发 proof");
    }

    @Test
    void solveChallengeShouldFailWhenChallengeExpired() {
        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN));

        assertEquals(CaptchaException.Kind.EXPIRED, exception.getKind());
    }

    @Test
    void challengeMetadataShouldBeConsumedOnlyOnce() {
        issue(EMAIL, CaptchaPurpose.LOGIN);
        solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN);

        engine.matchResult = true;
        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN));

        assertEquals(CaptchaException.Kind.EXPIRED, exception.getKind());
        assertEquals(1, engine.matchingCalls.get(), "第二次 solve 不应再进入 matching");
    }

    @Test
    void purposeMismatchShouldInvalidateChallengeWithoutMatching() {
        issue(EMAIL, CaptchaPurpose.LOGIN);

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.REGISTER));

        assertEquals(CaptchaException.Kind.EXPIRED, exception.getKind());
        assertEquals(0, engine.matchingCalls.get(), "用途不匹配不进入 matching");
    }

    @Test
    void emailFingerprintMismatchShouldInvalidateChallengeWithoutMatching() {
        issue(EMAIL, CaptchaPurpose.LOGIN);

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue("other@example.com", CaptchaPurpose.LOGIN));

        assertEquals(CaptchaException.Kind.EXPIRED, exception.getKind());
        assertEquals(0, engine.matchingCalls.get(), "邮箱不匹配不进入 matching");
    }

    @Test
    void consumeProofShouldSucceedOnceThenFail() {
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);

        humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN, EMAIL);

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN, EMAIL));
        assertEquals(CaptchaException.Kind.PROOF_INVALID, exception.getKind());
    }

    @Test
    void loginProofShouldNotConsumeForRegisterAndViceVersa() {
        CaptchaProofHolder loginProof = solve(EMAIL, CaptchaPurpose.LOGIN);
        engine.reset();
        CaptchaProofHolder registerProof = solve(EMAIL, CaptchaPurpose.REGISTER);

        CaptchaException loginAsRegister = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(loginProof.rawProof(), CaptchaPurpose.REGISTER, EMAIL));
        CaptchaException registerAsLogin = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(registerProof.rawProof(), CaptchaPurpose.LOGIN, EMAIL));

        assertEquals(CaptchaException.Kind.PROOF_INVALID, loginAsRegister.getKind());
        assertEquals(CaptchaException.Kind.PROOF_INVALID, registerAsLogin.getKind());
    }

    @Test
    void proofWithDifferentEmailShouldFail() {
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);

        CaptchaException exception = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN,
                        "other@example.com"));

        assertEquals(CaptchaException.Kind.PROOF_INVALID, exception.getKind());
    }

    @Test
    void missingBlankAndOversizedProofShouldFailUniformly() {
        for (String raw : new String[]{null, "", "   ", "x".repeat(1025)}) {
            CaptchaException exception = assertThrows(CaptchaException.class,
                    () -> humanVerification.consumeProof(raw, CaptchaPurpose.LOGIN, EMAIL));
            assertEquals(CaptchaException.Kind.PROOF_INVALID, exception.getKind(), "raw=" + (raw == null ? "null" : "len"));
            assertEquals(CaptchaErrors.MSG_PROOF_INVALID, exception.getMessage());
        }
    }

    @Test
    void tamperedOrRandomProofShouldFail() {
        solve(EMAIL, CaptchaPurpose.LOGIN);

        CaptchaException tampered = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof("tampered-proof-value", CaptchaPurpose.LOGIN, EMAIL));
        assertEquals(CaptchaException.Kind.PROOF_INVALID, tampered.getKind());
    }

    @Test
    void hundredConcurrentConsumersShouldProduceExactlyOneSuccess() throws Exception {
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);
        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN, EMAIL);
                    return true;
                } catch (CaptchaException e) {
                    if (e.getKind() != CaptchaException.Kind.PROOF_INVALID) {
                        throw e;
                    }
                    return false;
                }
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<Boolean> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        int successCount = 0;
        for (java.util.concurrent.Future<Boolean> future : futures) {
            if (future.get()) {
                successCount++;
            }
        }
        assertEquals(1, successCount, "同一 proof 并发消费恰好一个成功");
    }

    @Test
    void redisFailureAtAnyStageShouldFailClosed() {
        stateStore.failOnSaveMetadata = true;
        assertThrows(CaptchaException.class, () -> humanVerification.issueChallenge(CaptchaPurpose.LOGIN, EMAIL, CLIENT));

        stateStore.failOnSaveMetadata = false;
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);
        assertNotNull(proof);

        stateStore.failOnConsumeProof = true;
        CaptchaException consumeFailure = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN, EMAIL));
        assertEquals(CaptchaException.Kind.UNAVAILABLE, consumeFailure.getKind());

        stateStore.failOnConsumeProof = false;
        stateStore.failOnSaveProof = true;
        issue(EMAIL, CaptchaPurpose.LOGIN);
        CaptchaException solveFailure = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN));
        assertEquals(CaptchaException.Kind.UNAVAILABLE, solveFailure.getKind());
    }

    @Test
    void emailNormalizationShouldBeConsistentAcrossStages() {
        CaptchaChallenge challenge = humanVerification.issueChallenge(CaptchaPurpose.REGISTER,
                "  User@Example.COM  ", CLIENT);
        assertEquals(FakeEngine.CHALLENGE_ID, challenge.id());

        CaptchaProofHolder proof = solveWithoutIssue("user@example.com", CaptchaPurpose.REGISTER);

        humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.REGISTER, "USER@example.COM");
    }

    @Test
    void rateLimitedIssueAndSolveAndAuthShouldPropagate() {
        rateLimiter.failIssue = true;
        CaptchaException issueLimited = assertThrows(CaptchaException.class,
                () -> humanVerification.issueChallenge(CaptchaPurpose.LOGIN, EMAIL, CLIENT));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, issueLimited.getKind());

        rateLimiter.failIssue = false;
        rateLimiter.failSolve = true;
        issue(EMAIL, CaptchaPurpose.LOGIN);
        CaptchaException solveLimited = assertThrows(CaptchaException.class,
                () -> solveWithoutIssue(EMAIL, CaptchaPurpose.LOGIN));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, solveLimited.getKind());
        assertEquals(0, engine.matchingCalls.get(), "限流先于 metadata 消费执行");

        rateLimiter.failSolve = false;
        CaptchaProofHolder proof = solve(EMAIL, CaptchaPurpose.LOGIN);
        rateLimiter.failAuth = true;
        CaptchaException authLimited = assertThrows(CaptchaException.class,
                () -> humanVerification.consumeProof(proof.rawProof(), CaptchaPurpose.LOGIN, EMAIL));
        assertEquals(CaptchaException.Kind.RATE_LIMITED, authLimited.getKind());
    }

    private void issue(String email, CaptchaPurpose purpose) {
        humanVerification.issueChallenge(purpose, email, CLIENT);
    }

    private CaptchaProofHolder solve(String email, CaptchaPurpose purpose) {
        issue(email, purpose);
        return solveWithoutIssue(email, purpose);
    }

    private CaptchaProofHolder solveWithoutIssue(String email, CaptchaPurpose purpose) {
        var proof = humanVerification.solveChallenge(new CaptchaSolution(
                FakeEngine.CHALLENGE_ID, purpose, email, track()), CLIENT);
        return new CaptchaProofHolder(proof.rawProof(), proof.expiresInSeconds());
    }

    private static CaptchaTrack track() {
        return new CaptchaTrack(300, 180, 55, 180, 1000L, 1834L,
                List.of(new CaptchaTrack.TrackPoint(10f, 10f, 0f, "down"),
                        new CaptchaTrack.TrackPoint(120f, 11f, 834f, "up")));
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CaptchaProofHolder(String rawProof, long expiresInSeconds) {
    }

    private static final class FakeEngine implements CaptchaEngine {

        static final String CHALLENGE_ID = "SLIDER_TEST_1";

        volatile boolean matchResult = true;
        final AtomicInteger matchingCalls = new AtomicInteger();

        @Override
        public CaptchaChallenge generateSlider() {
            return new CaptchaChallenge(CHALLENGE_ID, "SLIDER", "data:image/jpeg;base64,xxx",
                    "data:image/png;base64,yyy", 600, 360, 110, 360);
        }

        @Override
        public boolean matching(String challengeId, CaptchaTrack track) {
            matchingCalls.incrementAndGet();
            return matchResult;
        }

        void reset() {
            matchResult = true;
        }
    }

    private static final class FakeStateStore implements CaptchaStateStore {

        final ConcurrentHashMap<String, VerificationBinding> metadata = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, VerificationBinding> proofs = new ConcurrentHashMap<>();
        volatile Duration lastMetadataTtl;
        volatile boolean failOnSaveMetadata;
        volatile boolean failOnSaveProof;
        volatile boolean failOnConsumeProof;

        @Override
        public void saveChallengeMetadata(String challengeId, CaptchaPurpose purpose, String subjectFingerprint,
                                          Duration ttl) {
            if (failOnSaveMetadata) {
                throw new IllegalStateException("redis down");
            }
            lastMetadataTtl = ttl;
            metadata.put(challengeId, new VerificationBinding(purpose, subjectFingerprint, Instant.now()));
        }

        @Override
        public Optional<VerificationBinding> consumeChallengeMetadata(String challengeId) {
            return Optional.ofNullable(metadata.remove(challengeId));
        }

        @Override
        public boolean saveProofIfAbsent(String proofHash, CaptchaPurpose purpose, String subjectFingerprint,
                                         Duration ttl) {
            if (failOnSaveProof) {
                throw new IllegalStateException("redis down");
            }
            return proofs.putIfAbsent(proofHash,
                    new VerificationBinding(purpose, subjectFingerprint, Instant.now())) == null;
        }

        @Override
        public Optional<VerificationBinding> consumeProof(String proofHash) {
            if (failOnConsumeProof) {
                throw new IllegalStateException("redis down");
            }
            return Optional.ofNullable(proofs.remove(proofHash));
        }
    }

    private static final class FakeRateLimiter implements CaptchaRateLimiter {

        volatile boolean failIssue;
        volatile boolean failSolve;
        volatile boolean failAuth;

        @Override
        public void checkIssueAllowed(ClientContext client) {
            if (failIssue) {
                throw new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED);
            }
        }

        @Override
        public void checkSolveAllowed(ClientContext client, String subjectFingerprint) {
            if (failSolve) {
                throw new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED);
            }
        }

        @Override
        public void checkAuthAllowed(CaptchaPurpose purpose, String subjectFingerprint) {
            if (failAuth) {
                throw new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED);
            }
        }

        @Override
        public void checkAuthIpAllowed(CaptchaPurpose purpose, ClientContext client) {
            if (failAuth) {
                throw new CaptchaException(CaptchaException.Kind.RATE_LIMITED, CaptchaErrors.MSG_RATE_LIMITED);
            }
        }
    }
}

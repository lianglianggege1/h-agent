package com.h.backend.captcha.application.impl;

import com.h.backend.captcha.application.CaptchaEngine;
import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.application.CaptchaStateStore;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaProof;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.captcha.domain.SubjectFingerprintCalculator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 两阶段一次性凭证核心实现：
 * issue 绑定 purpose + 邮箱指纹 -> solve 原子消费 metadata 与上游答案 -> 签发 proof -> consume 原子消费 proof。
 * Redis/引擎任一阶段异常均 fail closed，不签发 challenge/proof，不放行认证。
 */
public class HumanVerificationImpl implements HumanVerification {

    private static final Logger log = LoggerFactory.getLogger(HumanVerificationImpl.class);

    /** raw proof 最大长度，防超长输入造成无谓的哈希计算。 */
    private static final int RAW_PROOF_MAX_LENGTH = 1024;
    private static final int PROOF_HASH_RETRY = 3;
    private static final int PROOF_RANDOM_BYTES = 32;

    private final CaptchaEngine captchaEngine;
    private final CaptchaStateStore stateStore;
    private final CaptchaRateLimiter rateLimiter;
    private final SubjectFingerprintCalculator fingerprintCalculator;
    private final Duration challengeMetadataTtl;
    private final Duration proofTtl;
    private final MeterRegistry meterRegistry;

    private final SecureRandom secureRandom = new SecureRandom();

    public HumanVerificationImpl(CaptchaEngine captchaEngine,
                                 CaptchaStateStore stateStore,
                                 CaptchaRateLimiter rateLimiter,
                                 SubjectFingerprintCalculator fingerprintCalculator,
                                 Duration challengeMetadataTtl,
                                 Duration proofTtl,
                                 MeterRegistry meterRegistry) {
        this.captchaEngine = captchaEngine;
        this.stateStore = stateStore;
        this.rateLimiter = rateLimiter;
        this.fingerprintCalculator = fingerprintCalculator;
        this.challengeMetadataTtl = challengeMetadataTtl;
        this.proofTtl = proofTtl;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CaptchaChallenge issueChallenge(CaptchaPurpose purpose, String email, ClientContext client) {
        Timer.Sample sample = Timer.start(meterRegistry);
        rateLimiter.checkIssueAllowed(client);
        try {
            String subjectFingerprint = fingerprintCalculator.fingerprint(email);
            CaptchaChallenge challenge = captchaEngine.generateSlider();
            // metadata 写入失败必须 fail closed：不向客户端暴露该 challenge
            stateStore.saveChallengeMetadata(challenge.id(), purpose, subjectFingerprint, challengeMetadataTtl);
            meterRegistry.counter("captcha_challenge_issued_total").increment();
            return challenge;
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            meterRegistry.counter("captcha_challenge_issued_total", "outcome", "error").increment();
            log.error("[Captcha] issue 阶段验证码引擎或存储异常 type={}", e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        } finally {
            sample.stop(Timer.builder("captcha_operation_duration_seconds").tag("operation", "issue")
                    .description("验证码签发耗时").register(meterRegistry));
        }
    }

    @Override
    public CaptchaProof solveChallenge(CaptchaSolution solution, ClientContext client) {
        Timer.Sample sample = Timer.start(meterRegistry);
        rateLimiter.checkSolveAllowed(client, fingerprintCalculator.fingerprint(solution.email()));
        try {
            // 先原子消费 metadata：任一中断都使 challenge 不可继续使用，保持 fail closed
            CaptchaStateStore.VerificationBinding metadata =
                    stateStore.consumeChallengeMetadata(solution.challengeId())
                            .orElseGet(() -> {
                                meterRegistry.counter("captcha_challenge_solve_total", "outcome", "expired").increment();
                                return null;
                            });
            if (metadata == null) {
                throw new CaptchaException(CaptchaException.Kind.EXPIRED, CaptchaErrors.MSG_TAC_EXPIRED);
            }
            String subjectFingerprint = fingerprintCalculator.fingerprint(solution.email());
            boolean bindingMatched = metadata.purpose() == solution.purpose()
                    && metadata.subjectFingerprint().equals(subjectFingerprint);
            if (!bindingMatched) {
                // 用途或邮箱不匹配：challenge 已消费，同样失效，且不进入 matching
                meterRegistry.counter("captcha_challenge_solve_total", "outcome", "invalid_binding").increment();
                throw new CaptchaException(CaptchaException.Kind.EXPIRED, CaptchaErrors.MSG_TAC_EXPIRED);
            }

            boolean matched = captchaEngine.matching(solution.challengeId(), solution.track());
            if (!matched) {
                meterRegistry.counter("captcha_challenge_solve_total", "outcome", "mismatch").increment();
                throw new CaptchaException(CaptchaException.Kind.MISMATCH, CaptchaErrors.MSG_TAC_MISMATCH);
            }

            CaptchaProof proof = issueProof(solution.purpose(), subjectFingerprint);
            meterRegistry.counter("captcha_challenge_solve_total", "outcome", "success").increment();
            return proof;
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            meterRegistry.counter("captcha_challenge_solve_total", "outcome", "error").increment();
            log.error("[Captcha] solve 阶段验证码引擎异常 type={}", e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        } finally {
            sample.stop(Timer.builder("captcha_operation_duration_seconds").tag("operation", "solve")
                    .description("验证码校验耗时").register(meterRegistry));
        }
    }

    @Override
    public void consumeProof(String rawProof, CaptchaPurpose purpose, String email) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String purposeTag = purpose.name().toLowerCase(java.util.Locale.ROOT);
        rateLimiter.checkAuthAllowed(purpose, fingerprintCalculator.fingerprint(email));
        try {
            if (rawProof == null || rawProof.isBlank() || rawProof.length() > RAW_PROOF_MAX_LENGTH) {
                meterRegistry.counter("captcha_proof_consume_total", "outcome", "invalid", "purpose", purposeTag)
                        .increment();
                throw new CaptchaException(CaptchaException.Kind.PROOF_INVALID, CaptchaErrors.MSG_PROOF_INVALID);
            }
            String proofHash = sha256Hex(rawProof);
            CaptchaStateStore.VerificationBinding binding =
                    stateStore.consumeProof(proofHash)
                            .orElseGet(() -> {
                                meterRegistry.counter("captcha_proof_consume_total", "outcome", "invalid",
                                        "purpose", purposeTag).increment();
                                return null;
                            });
            // proof 失效、过期、重放、用途错误和邮箱错误对认证调用者呈现同一错误语义
            if (binding == null
                    || binding.purpose() != purpose
                    || !binding.subjectFingerprint().equals(fingerprintCalculator.fingerprint(email))) {
                if (binding != null) {
                    meterRegistry.counter("captcha_proof_consume_total", "outcome", "invalid", "purpose", purposeTag)
                            .increment();
                }
                throw new CaptchaException(CaptchaException.Kind.PROOF_INVALID, CaptchaErrors.MSG_PROOF_INVALID);
            }
            meterRegistry.counter("captcha_proof_consume_total", "outcome", "success", "purpose", purposeTag)
                    .increment();
        } catch (CaptchaException e) {
            throw e;
        } catch (RuntimeException e) {
            meterRegistry.counter("captcha_proof_consume_total", "outcome", "error", "purpose", purposeTag)
                    .increment();
            log.error("[Captcha] consume 阶段存储异常 type={}", e.getClass().getSimpleName(), e);
            throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE, e);
        } finally {
            sample.stop(Timer.builder("captcha_operation_duration_seconds").tag("operation", "consume")
                    .description("proof 消费耗时").register(meterRegistry));
        }
    }

    private CaptchaProof issueProof(CaptchaPurpose purpose, String subjectFingerprint) {
        for (int i = 0; i < PROOF_HASH_RETRY; i++) {
            byte[] random = new byte[PROOF_RANDOM_BYTES];
            secureRandom.nextBytes(random);
            String rawProof = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String proofHash = sha256Hex(rawProof);
            boolean saved = stateStore.saveProofIfAbsent(proofHash, purpose, subjectFingerprint, proofTtl);
            if (saved) {
                return new CaptchaProof(rawProof, proofTtl.toSeconds());
            }
            // 极低概率 key 冲突：重新生成随机值，不覆盖现有 proof
        }
        throw new CaptchaException(CaptchaException.Kind.UNAVAILABLE, CaptchaErrors.MSG_UNAVAILABLE);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

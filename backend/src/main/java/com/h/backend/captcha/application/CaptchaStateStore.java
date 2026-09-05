package com.h.backend.captcha.application;

import com.h.backend.captcha.domain.CaptchaPurpose;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * challenge metadata / proof 状态存储内部 seam。生产实现为 Redis，保证跨实例原子消费。
 */
public interface CaptchaStateStore {

    /** 写入 challenge metadata；写入失败抛 CaptchaException(UNAVAILABLE)。 */
    void saveChallengeMetadata(String challengeId, CaptchaPurpose purpose, String subjectFingerprint, Duration ttl);

    /** 原子读取并删除 challenge metadata。 */
    Optional<VerificationBinding> consumeChallengeMetadata(String challengeId);

    /** SET NX EX 写入 proof；key 已存在返回 false，不覆盖现有 proof。 */
    boolean saveProofIfAbsent(String proofHash, CaptchaPurpose purpose, String subjectFingerprint, Duration ttl);

    /** 原子读取并删除 proof。 */
    Optional<VerificationBinding> consumeProof(String proofHash);

    /**
     * challenge metadata 与 proof 共用的绑定信息。
     */
    record VerificationBinding(CaptchaPurpose purpose, String subjectFingerprint, Instant issuedAt) {
    }
}

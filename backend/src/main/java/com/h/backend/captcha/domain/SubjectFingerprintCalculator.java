package com.h.backend.captcha.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 邮箱规范化 + 专用密钥 HMAC-SHA-256 指纹。签发、校验、消费统一使用同一规则。
 */
public class SubjectFingerprintCalculator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    public static final String PREFIX = "hmac-sha256:";

    private final byte[] secret;

    public SubjectFingerprintCalculator(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "human-verification.subject-hmac-secret 必须配置（独立密钥，禁止复用 JWT_SECRET）");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String fingerprint(String email) {
        String normalized = normalizeEmail(email);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("计算 subject fingerprint 失败", e);
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

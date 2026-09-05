package com.h.backend.captcha.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * human-verification 配置。默认值与设计文档 §5.4 一致。
 */
@Data
@ConfigurationProperties(prefix = "human-verification")
public class HumanVerificationProperties {

    /** challenge metadata TTL，与上游 challenge TTL 对齐。 */
    private Duration challengeMetadataTtl = Duration.ofSeconds(120);

    /** proof TTL。 */
    private Duration proofTtl = Duration.ofSeconds(90);

    /** Redis key 前缀。 */
    private String keyPrefix = "h-agent:human-verification";

    /** 专用 HMAC 密钥，不复用 JWT_SECRET；为空时启动失败。 */
    private String subjectHmacSecret;

    private final RateLimit rateLimit = new RateLimit();

    @Data
    public static class RateLimit {
        /** issue challenge：来源 IP 每分钟次数。 */
        private int issuePerMinutePerIp = 20;
        /** solve challenge：来源 IP 每分钟次数。 */
        private int solvePerMinutePerIp = 20;
        /** solve challenge：subject fingerprint 每分钟次数。 */
        private int solvePerMinutePerSubject = 10;
        /** 登录/注册：来源 IP 每 10 分钟次数。 */
        private int authIpPer10Minutes = 10;
        /** 登录/注册：subject fingerprint 每 10 分钟次数。 */
        private int authSubjectPer10Minutes = 5;
    }
}

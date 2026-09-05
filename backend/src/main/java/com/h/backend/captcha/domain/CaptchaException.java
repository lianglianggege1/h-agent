package com.h.backend.captcha.domain;

/**
 * captcha 模块领域异常。协议翻译只发生在 CaptchaController（官方协议）
 * 与 AuthServiceImpl（项目协议）。
 */
public class CaptchaException extends RuntimeException {

    public enum Kind {
        /** 请求参数错误，HTTP 400。 */
        PARAM_INVALID,
        /** 滑块匹配失败，HTTP 200 + 官方码 4001。 */
        MISMATCH,
        /** challenge 过期或已消费，HTTP 200 + 官方码 4000。 */
        EXPIRED,
        /** proof 无效、过期、重放、用途或邮箱不匹配，统一语义。 */
        PROOF_INVALID,
        /** 触发限流，HTTP 429。 */
        RATE_LIMITED,
        /** Redis/验证码引擎不可用，fail closed，HTTP 503。 */
        UNAVAILABLE
    }

    private final Kind kind;

    public CaptchaException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CaptchaException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}

package com.h.backend.captcha.domain;

/**
 * captcha 模块统一错误码与文案。
 * 40001/40003/42901/50301 是项目协议错误码（code/message/data）；
 * 200/4001/4000 是官方 Web SDK 协议码（code/msg/data），仅在 CaptchaController 翻译。
 */
public final class CaptchaErrors {

    public static final int TAC_CODE_SUCCESS = 200;
    public static final int TAC_CODE_EXPIRED = 4000;
    public static final int TAC_CODE_MISMATCH = 4001;

    public static final int PARAM_INVALID = 40001;
    public static final int PROOF_INVALID = 40003;
    public static final int RATE_LIMITED = 42901;
    public static final int UNAVAILABLE = 50301;

    public static final String MSG_PARAM_INVALID = "参数错误";
    public static final String MSG_PROOF_INVALID = "请重新完成滑块验证";
    public static final String MSG_RATE_LIMITED = "操作过于频繁，请稍后再试";
    public static final String MSG_UNAVAILABLE = "验证服务暂时不可用，请稍后重试";
    public static final String MSG_TAC_MISMATCH = "验证失败，请重新尝试";
    public static final String MSG_TAC_EXPIRED = "验证已失效，请重新尝试";

    private CaptchaErrors() {
    }
}

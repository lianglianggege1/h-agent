package com.h.backend.captcha.application;

import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaProof;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.CaptchaSolution;
import com.h.backend.captcha.domain.ClientContext;

/**
 * 认证模块和验证码 Web 层共同使用的人机验证 seam。
 * 调用者只理解签发题目、求解题目、消费 proof 三个动作，
 * 不感知 tianai-captcha、Redis key、随机数、HMAC 或上游错误码。
 */
public interface HumanVerification {

    /** 生成一个绑定 purpose + 邮箱指纹 的 SLIDER challenge。 */
    CaptchaChallenge issueChallenge(CaptchaPurpose purpose, String email, ClientContext client);

    /** 校验轨迹；成功签发一次性 proof，失败抛 CaptchaException。 */
    CaptchaProof solveChallenge(CaptchaSolution solution, ClientContext client);

    /** 原子消费 proof；任何不匹配抛统一的 proof invalid 错误。 */
    void consumeProof(String rawProof, CaptchaPurpose purpose, String email);
}

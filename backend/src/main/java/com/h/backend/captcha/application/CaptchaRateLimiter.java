package com.h.backend.captcha.application;

import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.ClientContext;

/**
 * 验证码与认证动作的业务维度限流内部 seam。
 * 限流与滑块是两层独立防护：通过滑块不会清空认证限流。
 */
public interface CaptchaRateLimiter {

    /** issue challenge：来源 IP 维度。 */
    void checkIssueAllowed(ClientContext client);

    /** solve challenge：来源 IP + subject fingerprint 维度。 */
    void checkSolveAllowed(ClientContext client, String subjectFingerprint);

    /** 登录/注册 proof 消费：subject fingerprint 维度（设计 §10：5 次/10 分钟）。 */
    void checkAuthAllowed(CaptchaPurpose purpose, String subjectFingerprint);

    /** 登录/注册入口：来源 IP 维度（设计 §10：10 次/10 分钟），供 auth HTTP interceptor 调用。 */
    void checkAuthIpAllowed(CaptchaPurpose purpose, ClientContext client);
}

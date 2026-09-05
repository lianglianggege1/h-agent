package com.h.backend.captcha.application;

import com.h.backend.captcha.domain.CaptchaChallenge;
import com.h.backend.captcha.domain.CaptchaTrack;

/**
 * 验证码引擎内部 seam。生产实现为 tianai-captcha Adapter；
 * 调用方无法注入资源、模板、验证码类型或容差。
 */
public interface CaptchaEngine {

    /** 生成一个 SLIDER challenge（不含正确位置）。 */
    CaptchaChallenge generateSlider();

    /** 校验轨迹并原子消费上游正确答案，返回是否匹配。 */
    boolean matching(String challengeId, CaptchaTrack track);
}

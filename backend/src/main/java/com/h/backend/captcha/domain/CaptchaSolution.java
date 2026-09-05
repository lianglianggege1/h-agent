package com.h.backend.captcha.domain;

/**
 * 一次滑块校验请求：challenge id + 用途 + 邮箱 + 轨迹。
 */
public record CaptchaSolution(
        String challengeId,
        CaptchaPurpose purpose,
        String email,
        CaptchaTrack track) {
}

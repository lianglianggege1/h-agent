package com.h.backend.captcha.domain;

/**
 * 一次滑块题目。不包含正确位置，可直接序列化给前端。
 */
public record CaptchaChallenge(
        String id,
        String type,
        String backgroundImage,
        String templateImage,
        Integer backgroundImageWidth,
        Integer backgroundImageHeight,
        Integer templateImageWidth,
        Integer templateImageHeight) {
}

package com.h.backend.captcha.domain;

/**
 * challenge 校验成功后签发的一次性凭证。rawProof 只返回一次，不落日志。
 */
public record CaptchaProof(String rawProof, long expiresInSeconds) {
}

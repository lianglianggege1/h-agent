package com.h.backend.captcha.interfaces.dto;

/**
 * 官方 Web SDK 协议（code/msg/data）的响应 DTO。与项目 ApiResponse（code/message/data）隔离。
 */
public final class CaptchaResponses {

    private CaptchaResponses() {
    }

    public record TacApiResponse<T>(int code, String msg, T data) {

        public static <T> TacApiResponse<T> ok(T data) {
            return new TacApiResponse<>(200, "OK", data);
        }

        public static TacApiResponse<Void> error(int code, String msg) {
            return new TacApiResponse<>(code, msg, null);
        }
    }

    public record ChallengeData(
            String id,
            String type,
            String backgroundImage,
            String templateImage,
            Integer backgroundImageWidth,
            Integer backgroundImageHeight,
            Integer templateImageWidth,
            Integer templateImageHeight,
            Object data) {
    }

    public record ProofData(String captchaProof, long expiresIn) {
    }
}

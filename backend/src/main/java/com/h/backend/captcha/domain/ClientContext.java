package com.h.backend.captcha.domain;

/**
 * 经可信代理解析后的来源 IP 等限流信息。第一版不作为 proof 的强绑定字段。
 */
public record ClientContext(String clientIp) {

    public static ClientContext of(String clientIp) {
        return new ClientContext(clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
    }
}

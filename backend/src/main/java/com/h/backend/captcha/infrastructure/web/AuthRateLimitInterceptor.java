package com.h.backend.captcha.infrastructure.web;

import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.domain.CaptchaErrors;
import com.h.backend.captcha.domain.CaptchaException;
import com.h.backend.captcha.domain.CaptchaPurpose;
import com.h.backend.captcha.domain.ClientContext;
import com.h.backend.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * 登录/注册入口的 IP 维度限流（设计 §10：10 次/10 分钟），在解析请求体之前执行。
 * 客户端 IP 只取直连地址，不信任任意 X-Forwarded-For。
 * 限流/依赖故障按项目协议（code/message/data）写回 429/503，不进入 Controller。
 */
@RequiredArgsConstructor
public class AuthRateLimitInterceptor implements HandlerInterceptor {

    private final CaptchaRateLimiter captchaRateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        CaptchaPurpose purpose = path.endsWith("/register") ? CaptchaPurpose.REGISTER : CaptchaPurpose.LOGIN;
        try {
            captchaRateLimiter.checkAuthIpAllowed(purpose, ClientContext.of(request.getRemoteAddr()));
        } catch (CaptchaException e) {
            writeError(response, switch (e.getKind()) {
                case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            }, switch (e.getKind()) {
                case RATE_LIMITED -> CaptchaErrors.RATE_LIMITED;
                default -> CaptchaErrors.UNAVAILABLE;
            }, switch (e.getKind()) {
                case RATE_LIMITED -> CaptchaErrors.MSG_RATE_LIMITED;
                default -> CaptchaErrors.MSG_UNAVAILABLE;
            });
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, int code, String message) {
        try {
            response.setStatus(status.value());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
        } catch (Exception ignored) {
            // 写出失败时仅标记状态码，响应中断由容器处理
            response.setStatus(status.value());
        }
    }
}

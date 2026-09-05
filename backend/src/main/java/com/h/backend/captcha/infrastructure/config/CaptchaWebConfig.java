package com.h.backend.captcha.infrastructure.config;

import com.h.backend.captcha.application.CaptchaRateLimiter;
import com.h.backend.captcha.infrastructure.web.AuthRateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/**
 * 登录/注册 IP 维度限流 interceptor 注册。
 */
@Configuration
@RequiredArgsConstructor
public class CaptchaWebConfig implements WebMvcConfigurer {

    private final CaptchaRateLimiter captchaRateLimiter;
    private final ObjectMapper objectMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthRateLimitInterceptor(captchaRateLimiter, objectMapper))
                .addPathPatterns("/api/auth/login", "/api/auth/register");
    }
}

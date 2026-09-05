package com.h.backend.auth;

import com.h.backend.auth.interfaces.dto.AuthUserResponse;
import com.h.backend.auth.interfaces.dto.LoginRequest;
import com.h.backend.auth.interfaces.dto.LoginResponse;
import com.h.backend.auth.interfaces.dto.RegisterRequest;
import com.h.backend.auth.application.AuthService;
import com.h.backend.captcha.application.HumanVerification;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * proof 门禁行为由 AuthCaptchaIntegrationTest 覆盖；本类 mock HumanVerification
 * 验证密码、重复邮箱与 JWT 的既有服务行为保持不变。
 */
@SpringBootTest
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private HumanVerification humanVerification;

    @Test
    void shouldRegisterUserWithDefaultRole() {
        RegisterRequest request = new RegisterRequest(uniqueEmail(), "Password123", null);

        AuthUserResponse response = authService.register(request);

        assertNotNull(response.userId());
        assertEquals(request.email(), response.email());
        assertEquals("USER", response.role());
    }

    @Test
    void shouldRejectDuplicateEmail() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123", null));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> authService.register(new RegisterRequest(email, "Password123", null))
        );

        assertEquals(40002, exception.getCode());
    }

    @Test
    void shouldRejectWrongPassword() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123", null));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> authService.login(new LoginRequest(email, "WrongPassword123", null))
        );

        assertEquals(40101, exception.getCode());
    }

    @Test
    void shouldLoginAndReturnJwtContainingUserId() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123", null));

        LoginResponse response = authService.login(new LoginRequest(email, "Password123", null));
        Claims claims = jwtTokenProvider.parse(response.accessToken());

        assertEquals("Bearer", response.tokenType());
        assertEquals(7200, response.expiresIn());
        assertEquals(email, claims.getSubject());
        assertEquals(response.user().userId(), ((Number) claims.get("user_id")).longValue());
        assertEquals("USER", claims.get("role"));
    }

    private String uniqueEmail() {
        return "auth_" + System.nanoTime() + "@example.com";
    }
}

package com.h.backend.auth;

import com.h.backend.auth.dto.AuthUserResponse;
import com.h.backend.auth.dto.LoginRequest;
import com.h.backend.auth.dto.LoginResponse;
import com.h.backend.auth.dto.RegisterRequest;
import com.h.backend.auth.service.AuthService;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void shouldRegisterUserWithDefaultRole() {
        RegisterRequest request = new RegisterRequest(uniqueEmail(), "Password123");

        AuthUserResponse response = authService.register(request);

        assertNotNull(response.userId());
        assertEquals(request.email(), response.email());
        assertEquals("USER", response.role());
    }

    @Test
    void shouldRejectDuplicateEmail() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> authService.register(new RegisterRequest(email, "Password123"))
        );

        assertEquals(40002, exception.getCode());
    }

    @Test
    void shouldRejectWrongPassword() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> authService.login(new LoginRequest(email, "WrongPassword123"))
        );

        assertEquals(40101, exception.getCode());
    }

    @Test
    void shouldLoginAndReturnJwtContainingUserId() {
        String email = uniqueEmail();
        authService.register(new RegisterRequest(email, "Password123"));

        LoginResponse response = authService.login(new LoginRequest(email, "Password123"));
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

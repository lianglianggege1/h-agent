package com.h.backend.auth.controller;

import com.h.backend.auth.dto.AuthUserResponse;
import com.h.backend.auth.dto.LoginRequest;
import com.h.backend.auth.dto.LoginResponse;
import com.h.backend.auth.dto.RegisterRequest;
import com.h.backend.auth.service.AuthService;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.security.AuthCookieHelper;
import com.h.backend.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthCookieHelper authCookieHelper;

    public AuthController(AuthService authService, AuthCookieHelper authCookieHelper) {
        this.authService = authService;
        this.authCookieHelper = authCookieHelper;
    }

    @PostMapping("/register")
    public ApiResponse<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response
    ) {
        LoginResponse loginResponse = authService.login(request);
        authCookieHelper.writeAccessTokenCookie(response, loginResponse.accessToken());
        return ApiResponse.ok(loginResponse);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authCookieHelper.clearAccessTokenCookie(response);
        return ApiResponse.ok(null);
    }

    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ApiResponse.ok(new AuthUserResponse(principal.userId(), principal.email(), principal.role()));
    }
}

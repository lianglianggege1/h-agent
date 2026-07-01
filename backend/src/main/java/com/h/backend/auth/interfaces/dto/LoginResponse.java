package com.h.backend.auth.interfaces.dto;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        AuthUserResponse user
) {
}

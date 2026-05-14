package com.h.backend.auth.dto;

public record AuthUserResponse(
        Long userId,
        String email,
        String role
) {
}

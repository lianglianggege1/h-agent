package com.h.backend.auth.interfaces.dto;

public record AuthUserResponse(
        Long userId,
        String email,
        String role
) {
}

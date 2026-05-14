package com.h.backend.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenProviderTest {

    private final JwtTokenProvider provider = new JwtTokenProvider(
            "change-this-to-256-bit-secret-change-this",
            7200
    );

    @Test
    void shouldCreateTokenContainingUserIdAndRole() {
        String token = provider.generateToken(10001L, "user@example.com", "USER");

        Claims claims = provider.parse(token);

        assertEquals("user@example.com", claims.getSubject());
        assertEquals(10001L, ((Number) claims.get("user_id")).longValue());
        assertEquals("USER", claims.get("role"));
        assertTrue(provider.isValid(token));
    }
}

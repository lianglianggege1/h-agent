package com.h.backend.security;

import java.security.Principal;

public record AuthUserPrincipal(Long userId, String email, String role) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}

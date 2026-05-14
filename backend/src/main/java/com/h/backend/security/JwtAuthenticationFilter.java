package com.h.backend.security;

import com.h.backend.common.context.LocalThread;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthCookieHelper authCookieHelper;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, AuthCookieHelper authCookieHelper) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authCookieHelper = authCookieHelper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String token = authCookieHelper.resolveAccessToken(request);
            if (token != null) {
                if (jwtTokenProvider.isValid(token)) {
                    Claims claims = jwtTokenProvider.parse(token);
                    Long userId = ((Number) claims.get("user_id")).longValue();
                    String email = claims.getSubject();
                    String role = String.valueOf(claims.get("role"));

                    LocalThread.setUser(userId, email);

                    AuthUserPrincipal principal = new AuthUserPrincipal(userId, email, role);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            LocalThread.clear();
        }
    }
}

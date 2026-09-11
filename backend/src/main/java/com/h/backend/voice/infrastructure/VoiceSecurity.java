package com.h.backend.voice.infrastructure;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class VoiceSecurity {
    @Bean @Order(0)
    SecurityFilterChain voiceInternalSecurity(HttpSecurity http,VoiceProperties properties)throws Exception {
        return http.securityMatcher("/internal/voice/**","/internal/livekit/webhook")
                .csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/internal/livekit/webhook").permitAll().anyRequest().authenticated())
                .addFilterBefore(new OncePerRequestFilter(){
                    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
                        if(request.getRequestURI().equals("/internal/livekit/webhook")){chain.doFilter(request,response);return;}
                        String bearer=request.getHeader("Authorization");
                        String expected=properties.getWorkerToken();
                        if(!properties.isEnabled() || expected.length()<32 || bearer==null || !MessageDigest.isEqual(("Bearer "+expected).getBytes(StandardCharsets.UTF_8),bearer.getBytes(StandardCharsets.UTF_8))){response.sendError(401);return;}
                        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("voice-worker",null,java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("VOICE_WORKER")));
                        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
                        chain.doFilter(request,response);
                    }
                },UsernamePasswordAuthenticationFilter.class).build();
    }
}

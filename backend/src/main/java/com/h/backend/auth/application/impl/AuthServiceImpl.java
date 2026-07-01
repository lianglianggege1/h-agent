package com.h.backend.auth.application.impl;

import com.h.backend.auth.interfaces.dto.AuthUserResponse;
import com.h.backend.auth.interfaces.dto.LoginRequest;
import com.h.backend.auth.interfaces.dto.LoginResponse;
import com.h.backend.auth.interfaces.dto.RegisterRequest;
import com.h.backend.auth.application.AuthService;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.JwtTokenProvider;
import com.h.backend.user.infrastructure.persistence.entity.UserEntity;
import com.h.backend.user.infrastructure.persistence.entity.UserRoleEntity;
import com.h.backend.user.infrastructure.persistence.mapper.UserMapper;
import com.h.backend.user.infrastructure.persistence.mapper.UserRoleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    private static final short STATUS_ENABLED = 1;
    private static final String DEFAULT_ROLE = "USER";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final long expirationSeconds;

    public AuthServiceImpl(
            UserMapper userMapper,
            UserRoleMapper userRoleMapper,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            @Value("${jwt.expiration-seconds}") long expirationSeconds
    ) {
        this.userMapper = userMapper;
        this.userRoleMapper = userRoleMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.expirationSeconds = expirationSeconds;
    }

    @Override
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        if (userMapper.selectByEmail(request.email()) != null) {
            throw new BusinessException(40002, "邮箱已注册");
        }

        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(STATUS_ENABLED);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        UserRoleEntity role = new UserRoleEntity();
        role.setUserId(user.getId());
        role.setRoleCode(DEFAULT_ROLE);
        role.setCreatedAt(now);
        userRoleMapper.insert(role);

        return new AuthUserResponse(user.getId(), user.getEmail(), DEFAULT_ROLE);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.selectByEmail(request.email());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(40101, "账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw new BusinessException(40102, "账号被禁用");
        }

        UserRoleEntity role = userRoleMapper.selectFirstByUserId(user.getId());
        String roleCode = role == null ? DEFAULT_ROLE : role.getRoleCode();
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail(), roleCode);
        AuthUserResponse userResponse = new AuthUserResponse(user.getId(), user.getEmail(), roleCode);
        return new LoginResponse(token, "Bearer", expirationSeconds, userResponse);
    }
}

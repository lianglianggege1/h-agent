package com.h.backend.auth.service;

import com.h.backend.auth.dto.AuthUserResponse;
import com.h.backend.auth.dto.LoginRequest;
import com.h.backend.auth.dto.LoginResponse;
import com.h.backend.auth.dto.RegisterRequest;

public interface AuthService {

    AuthUserResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}

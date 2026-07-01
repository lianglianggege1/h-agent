package com.h.backend.auth.application;

import com.h.backend.auth.interfaces.dto.AuthUserResponse;
import com.h.backend.auth.interfaces.dto.LoginRequest;
import com.h.backend.auth.interfaces.dto.LoginResponse;
import com.h.backend.auth.interfaces.dto.RegisterRequest;

public interface AuthService {

    AuthUserResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}

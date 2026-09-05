import { apiFetch } from "./http";

export type AuthUser = {
  userId: number;
  email: string;
  role: string;
};

export type LoginResult = {
  tokenType: "Bearer";
  expiresIn: number;
  user: AuthUser;
};

// 获取当前用户信息
export function getCurrentUser() {
  return apiFetch<AuthUser>("/api/auth/me");
}

export function logout() {
  return apiFetch<null>("/api/auth/logout", {
    method: "POST",
    body: JSON.stringify({}),
  });
}

export function register(payload: { email: string; password: string; captchaProof: string }) {
  return apiFetch<AuthUser>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function login(payload: { email: string; password: string; captchaProof: string }) {
  return apiFetch<LoginResult>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

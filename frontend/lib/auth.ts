import { apiFetch } from "./http";

export type AuthUser = {
  userId: number;
  email: string;
  role: string;
};

export type LoginResult = {
  accessToken: string;
  tokenType: "Bearer";
  expiresIn: number;
  user: AuthUser;
};

export function register(payload: { email: string; password: string }) {
  return apiFetch<AuthUser>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function login(payload: { email: string; password: string }) {
  return apiFetch<LoginResult>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

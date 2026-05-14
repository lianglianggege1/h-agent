const ACCESS_TOKEN_KEY = "h_agent_access_token";
const POST_LOGIN_REDIRECT_KEY = "h_agent_post_login_redirect";

export function saveAccessToken(token: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(ACCESS_TOKEN_KEY, token);
}

export function getAccessToken() {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function clearAccessToken() {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(ACCESS_TOKEN_KEY);
}

export function savePostLoginRedirect(path: string) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(POST_LOGIN_REDIRECT_KEY, path);
}

export function consumePostLoginRedirect() {
  if (typeof window === "undefined") return null;
  const value = window.localStorage.getItem(POST_LOGIN_REDIRECT_KEY);
  if (value) {
    window.localStorage.removeItem(POST_LOGIN_REDIRECT_KEY);
  }
  return value;
}

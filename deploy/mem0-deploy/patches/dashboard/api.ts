import axios, { AxiosError, AxiosInstance } from "axios";

let cachedToken: string | null = null;
const LOGIN_PATH = "/login";

// Runtime API base: derive from the browser's current address (protocol +
// hostname + API port) so the dashboard works no matter which host/IP it is
// opened from — localhost, 127.0.0.1, LAN IP, or hostname. This removes the
// build-time baked NEXT_PUBLIC_API_URL which breaks whenever the host IP
// changes. SSR falls back to the server-side env var.
export const getApiBaseUrl = (): string => {
  if (typeof window !== "undefined") {
    return `${window.location.protocol}//${window.location.hostname}:8888`;
  }
  return process.env.NEXT_PUBLIC_API_URL || "";
};

export const setAccessToken = (token: string | null) => {
  cachedToken = token;
};

export const getAccessToken = (): string | null => {
  return cachedToken;
};

const handleTokenError = () => {
  cachedToken = null;
};

const redirectToLogin = () => {
  if (typeof window !== "undefined") {
    window.location.href = LOGIN_PATH;
  }
};

const refreshAccessToken = async () => {
  const refreshResponse = await fetch("/api/auth/refresh", {
    method: "POST",
    credentials: "include",
  });

  if (!refreshResponse.ok) {
    return null;
  }

  const data = await refreshResponse.json();
  setAccessToken(data.access_token);
  return data.access_token as string;
};

const createApi = (): AxiosInstance & {
  postStream: (url: string, data: unknown) => Promise<Response>;
} => {
  const api = axios.create({
    baseURL: getApiBaseUrl(),
  });

  api.interceptors.request.use(
    async (config) => {
      if (cachedToken) {
        config.headers = config.headers ?? {};
        config.headers.Authorization = `Bearer ${cachedToken}`;
      }
      return config;
    },
    (error) => {
      return Promise.reject(error);
    },
  );

  api.interceptors.response.use(
    (response) => response,
    async (error: AxiosError<{ error?: string }>) => {
      if (error.response?.status === 401) {
        handleTokenError();

        try {
          const nextToken = await refreshAccessToken();
          if (nextToken && error.config) {
            error.config.headers = error.config.headers ?? {};
            error.config.headers.Authorization = `Bearer ${nextToken}`;
            return api.request(error.config);
          }
        } catch {}

        handleTokenError();
        redirectToLogin();
      }

      if (error.response?.data?.error) {
        return Promise.reject(error.response.data.error);
      }

      return Promise.reject(error);
    },
  );

  const postStream = async (url: string, data: unknown): Promise<Response> => {
    const response = await fetch(`${getApiBaseUrl()}${url}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: cachedToken ? `Bearer ${cachedToken}` : "",
      },
      body: JSON.stringify(data),
    });

    if (response.status === 401) {
      handleTokenError();
      redirectToLogin();
      throw new Error("Unauthorized");
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.error || "Request failed");
    }

    return response;
  };

  return Object.assign(api, { postStream });
};

export const api = createApi();

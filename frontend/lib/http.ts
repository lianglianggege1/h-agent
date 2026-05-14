import { getAccessToken } from "./session";

export type ApiResponse<T> = {
  code: number;
  message: string;
  data: T | null;
};

async function parseApiResponse<T>(response: Response) {
  try {
    return (await response.json()) as ApiResponse<T>;
  } catch {
    throw new Error("请求失败");
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");

  const token = getAccessToken();
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, {
    ...init,
    headers,
  });
  const body = await parseApiResponse<T>(response);

  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || "请求失败");
  }

  return body.data as T;
}

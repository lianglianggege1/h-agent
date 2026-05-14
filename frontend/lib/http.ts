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

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "include",
  });
  const body = await parseApiResponse<T>(response);

  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || "请求失败");
  }

  return body.data as T;
}

export async function apiStream(
  path: string,
  init: RequestInit,
  handlers: {
    onChunk: (value: string) => void;
    onDone?: (value: string) => void;
    onError?: (message: string) => void;
  },
) {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "include",
  });

  if (!response.ok) {
    const body = await parseApiResponse<null>(response);
    throw new Error(body.message || "请求失败");
  }

  if (!response.body) {
    throw new Error("浏览器不支持流式响应");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop() ?? "";

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line) continue;

      const event = JSON.parse(line) as { type: string; content: string };
      if (event.type === "chunk") {
        handlers.onChunk(event.content);
      } else if (event.type === "done") {
        handlers.onDone?.(event.content);
      } else if (event.type === "error") {
        handlers.onError?.(event.content);
        throw new Error(event.content || "请求失败");
      }
    }
  }
}

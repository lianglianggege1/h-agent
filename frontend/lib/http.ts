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

export async function apiFormFetch<T>(path: string, body: FormData, init: Omit<RequestInit, "body"> = {}) {
  const headers = new Headers(init.headers);
  headers.delete("Content-Type");

  const response = await fetch(path, {
    ...init,
    method: init.method ?? "POST",
    body,
    headers,
    credentials: "include",
  });
  const parsedBody = await parseApiResponse<T>(response);

  if (!response.ok || parsedBody.code !== 0) {
    throw new Error(parsedBody.message || "请求失败");
  }

  return parsedBody.data as T;
}

export async function apiStream(
  path: string,
  init: RequestInit,
  handlers: {
    onReasoning?: (value: string) => void;
    onChunk: (value: string) => void;
    onDone?: (value: string) => void;
    onBlocked?: (message: string) => void;
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

  const flushBlocks = (final = false) => {
    buffer = buffer.replace(/\r\n/g, "\n");
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() ?? "";

    const candidateBlocks = final && buffer.trim() ? [...blocks, buffer] : blocks;
    if (final) {
      buffer = "";
    }

    for (const rawBlock of candidateBlocks) {
      const block = rawBlock.trim();
      if (!block) continue;

      const lines = block.split("\n");
      const eventLine = lines.find((line) => line.startsWith("event:"));
      const dataLine = lines.find((line) => line.startsWith("data:"));
      if (!dataLine) continue;

      const eventName = eventLine?.slice("event:".length).trim();
      const payload = JSON.parse(dataLine.slice("data:".length).trim()) as {
        type: string;
        content: string;
      };
      const eventType = eventName || payload.type;

      if (eventType === "reasoning") {
        handlers.onReasoning?.(payload.content);
      } else if (eventType === "chunk") {
        handlers.onChunk(payload.content);
      } else if (eventType === "done") {
        handlers.onDone?.(payload.content);
      } else if (eventType === "blocked") {
        handlers.onBlocked?.(payload.content);
      } else if (eventType === "error") {
        handlers.onError?.(payload.content);
        throw new Error(payload.content || "请求失败");
      }
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      flushBlocks(true);
      break;
    }

    buffer += decoder.decode(value, { stream: true });
    flushBlocks();
  }
}

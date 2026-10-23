
export type ApiResponse<T> = {
  code: number;
  message: string;
  data: T | null;
};

export type AgentStepPayload = {
  runId: string | null;
  agentId: string | null;
  invocationId: string;
  nodeId: string;
  nodeName: string;
  topology: string;
  status: "running" | "completed" | "failed";
  depth: number | null;
  sequence: number;
};

async function parseApiResponse<T>(response: Response) {
  try {
    return (await response.json()) as ApiResponse<T>;
  } catch {
    throw new Error("请求失败");
  }
}

/** 携带响应 code 与 data 的请求错误：message 行为与普通 Error 一致。 */
export class ApiError extends Error {
  readonly code: number;
  readonly data: unknown;

  constructor(message: string, code: number, data: unknown) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.data = data;
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  const method = init.method ?? "GET";
  const startedAt = performance.now();
  console.info(`[api] >>> ${method} ${path} body=${typeof init.body === "string" ? init.body : ""}`);

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: "include",
  });
  const bodyText = await response.text();
  let body: ApiResponse<T>;
  try {
    body = JSON.parse(bodyText) as ApiResponse<T>;
  } catch {
    console.warn(`[api] xxx ${method} ${path} -> HTTP ${response.status} 非JSON响应: ${bodyText.slice(0, 500)}`);
    throw new Error("请求失败");
  }

  if (!response.ok || body.code !== 0) {
    console.warn(`[api] xxx ${method} ${path} -> HTTP ${response.status} code=${body.code} message=${body.message} costMs=${Math.round(performance.now() - startedAt)}`, body.data);
    throw new ApiError(body.message || "请求失败", body.code, body.data);
  }

  console.info(`[api] <<< ${method} ${path} -> HTTP ${response.status} code=0 costMs=${Math.round(performance.now() - startedAt)}`);
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
    throw new ApiError(parsedBody.message || "请求失败", parsedBody.code, parsedBody.data);
  }

  return parsedBody.data as T;
}

import type { ChatSessionMessage } from "./chat-sessions";
import type { HarnessAgentEvent } from "./harness-subagent-types";

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

type ApiStreamOptions = {
  inactivityTimeoutMs?: number;
};

const DEFAULT_STREAM_INACTIVITY_TIMEOUT_MS = 50_000;
const STREAM_INACTIVITY_MESSAGE = "连接长时间无响应，请稍后重试";

class StreamInactivityError extends Error {}

function linkedAbortController(signal?: AbortSignal | null) {
  const controller = new AbortController();
  if (!signal) {
    return { controller, unlink: () => {} };
  }

  const abort = () => controller.abort(signal.reason);
  if (signal.aborted) {
    abort();
    return { controller, unlink: () => {} };
  }

  signal.addEventListener("abort", abort, { once: true });
  return {
    controller,
    unlink: () => signal.removeEventListener("abort", abort),
  };
}

async function withInactivityTimeout<T>(
  operation: () => Promise<T>,
  timeoutMs: number,
  onTimeout: () => void,
) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let timedOut = false;

  try {
    return await Promise.race([
      operation(),
      new Promise<T>((_resolve, reject) => {
        timer = setTimeout(() => {
          timedOut = true;
          reject(new StreamInactivityError(STREAM_INACTIVITY_MESSAGE));
          onTimeout();
        }, timeoutMs);
      }),
    ]);
  } catch (error) {
    if (timedOut) {
      throw new StreamInactivityError(STREAM_INACTIVITY_MESSAGE);
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

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
    onUserMessage?: (message: ChatSessionMessage) => void;
    onDone?: (value: string, message?: ChatSessionMessage, payload?: unknown) => void;
    onImage?: (message: ChatSessionMessage) => void;
    onAgentStep?: (payload: AgentStepPayload) => void;
    onHarnessEvent?: (payload: HarnessAgentEvent) => void;
    onBlocked?: (message: string) => void;
    onError?: (message: string) => void;
  },
  options: ApiStreamOptions = {},
) {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  const inactivityTimeoutMs = options.inactivityTimeoutMs ?? DEFAULT_STREAM_INACTIVITY_TIMEOUT_MS;
  const { controller: watchdogController, unlink } = linkedAbortController(init.signal);

  try {
    let response: Response;
    try {
      response = await withInactivityTimeout(
        () => fetch(path, {
          ...init,
          headers,
          credentials: "include",
          signal: watchdogController.signal,
        }),
        inactivityTimeoutMs,
        () => watchdogController.abort(),
      );
    } catch (error) {
      if (error instanceof StreamInactivityError) {
        handlers.onError?.(STREAM_INACTIVITY_MESSAGE);
        throw new Error(STREAM_INACTIVITY_MESSAGE);
      }
      throw error;
    }

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
    let terminalEventReceived = false;

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
          message?: ChatSessionMessage;
          payload?: unknown;
        };
        const eventType = eventName || payload.type;

        if (eventType === "reasoning") {
          handlers.onReasoning?.(payload.content);
        } else if (eventType === "chunk") {
          handlers.onChunk(payload.content);
        } else if (eventType === "user_message" && payload.message) {
          handlers.onUserMessage?.(payload.message);
        } else if (eventType === "done") {
          terminalEventReceived = true;
          handlers.onDone?.(payload.content, payload.message, payload.payload);
        } else if ((eventType === "image" || eventType === "resource") && payload.message) {
          handlers.onImage?.(payload.message);
        } else if (eventType === "agent_step" && payload.payload) {
          handlers.onAgentStep?.(payload.payload as AgentStepPayload);
        } else if (eventType === "harness_event" && payload.payload) {
          handlers.onHarnessEvent?.(payload.payload as HarnessAgentEvent);
        } else if (eventType === "blocked") {
          terminalEventReceived = true;
          handlers.onBlocked?.(payload.content);
        } else if (eventType === "error") {
          terminalEventReceived = true;
          handlers.onError?.(payload.content);
          throw new Error(payload.content || "请求失败");
        }
      }
    };

    while (true) {
      let readResult: ReadableStreamReadResult<Uint8Array>;
      try {
        readResult = await withInactivityTimeout(
          () => reader.read(),
          inactivityTimeoutMs,
          () => {
            watchdogController.abort();
            void reader.cancel().catch(() => undefined);
          },
        );
      } catch (error) {
        if (error instanceof StreamInactivityError) {
          handlers.onError?.(STREAM_INACTIVITY_MESSAGE);
          throw new Error(STREAM_INACTIVITY_MESSAGE);
        }
        throw error;
      }

      const { done, value } = readResult;
      if (done) {
        flushBlocks(true);
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      flushBlocks();
    }

    if (!terminalEventReceived) {
      const message = "连接异常中断，请稍后重试";
      handlers.onError?.(message);
      throw new Error(message);
    }
  } finally {
    unlink();
  }
}

/**
 * Attach to an already-running child Agent Session. Unlike apiStream this does not start a turn;
 * it only consumes replayed/live harness events and may be opened again after navigation.
 */
export async function apiHarnessEventStream(
  path: string,
  onHarnessEvent: (payload: HarnessAgentEvent) => void,
  signal?: AbortSignal,
  options: ApiStreamOptions = {},
) {
  const inactivityTimeoutMs = options.inactivityTimeoutMs ?? DEFAULT_STREAM_INACTIVITY_TIMEOUT_MS;
  const { controller, unlink } = linkedAbortController(signal);
  try {
    const response = await withInactivityTimeout(
      () => fetch(path, {
        method: "GET",
        headers: { Accept: "text/event-stream" },
        credentials: "include",
        signal: controller.signal,
      }),
      inactivityTimeoutMs,
      () => controller.abort(),
    );
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
      const candidates = final && buffer.trim() ? [...blocks, buffer] : blocks;
      if (final) buffer = "";

      for (const rawBlock of candidates) {
        const lines = rawBlock.trim().split("\n");
        const eventLine = lines.find((line) => line.startsWith("event:"));
        const dataLine = lines.find((line) => line.startsWith("data:"));
        if (!dataLine || eventLine?.slice("event:".length).trim() !== "harness_event") continue;
        const envelope = JSON.parse(dataLine.slice("data:".length).trim()) as {
          payload?: HarnessAgentEvent;
        };
        if (envelope.payload) onHarnessEvent(envelope.payload);
      }
    };

    while (!controller.signal.aborted) {
      const { done, value } = await withInactivityTimeout(
        () => reader.read(),
        inactivityTimeoutMs,
        () => {
          controller.abort();
          void reader.cancel().catch(() => undefined);
        },
      );
      if (done) {
        flushBlocks(true);
        return;
      }
      buffer += decoder.decode(value, { stream: true });
      flushBlocks();
    }
  } catch (error) {
    if (signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) return;
    if (error instanceof StreamInactivityError) {
      throw new Error(STREAM_INACTIVITY_MESSAGE);
    }
    throw error;
  } finally {
    unlink();
  }
}

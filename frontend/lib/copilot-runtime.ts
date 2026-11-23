import { HttpAgent, type RunAgentInput } from "@ag-ui/client";
import {
  AgentRunner,
  CopilotRuntime,
  createCopilotRuntimeHandler,
  type AgentRunnerConnectRequest,
  type AgentRunnerIsRunningRequest,
  type AgentRunnerRunRequest,
  type AgentRunnerStopRequest,
} from "@copilotkit/runtime/v2";
import { from, switchMap } from "rxjs";

const backend = process.env.BACKEND_API_BASE_URL ?? "http://localhost:8083";

type ThreadStatus = { activeRunId: string | null; executing?: boolean };

/** Request-scoped SDK runner. Java remains the owner of execution and persistence. */
class JavaAgentRunner extends AgentRunner {
  private readonly authHeaders: Record<string, string>;
  constructor(authHeaders: Record<string, string>) { super(); this.authHeaders = authHeaders; }

  private agent() {
    return new HttpAgent({ url: backend + "/api/chat/agui", headers: this.authHeaders });
  }

  private async thread(threadId: string): Promise<ThreadStatus> {
    const response = await fetch(backend + "/api/chat/agui/threads/" + encodeURIComponent(threadId), {
      headers: this.authHeaders,
      cache: "no-store",
    });
    if (!response.ok) throw new Error("无法读取会话状态（" + response.status + "）");
    const body = await response.json();
    if (body.code !== 0) throw new Error(body.message || "无法读取会话状态");
    return body.data;
  }

  run({ input }: AgentRunnerRunRequest) {
    // Product history is server-owned. UI activity/tool history is never submitted
    // as model context or interpreted as a new turn.
    const messages = input.messages.filter((message) => message.role === "user" && typeof message.content === "string").slice(-1);
    return this.agent().run({ ...input, messages, state: {}, tools: [], context: [] });
  }

  connect({ threadId }: AgentRunnerConnectRequest) {
    return from(this.thread(threadId)).pipe(switchMap((status) => {
      const input: RunAgentInput = {
        threadId,
        runId: status.activeRunId ?? crypto.randomUUID(),
        messages: [], tools: [], context: [], state: {}, forwardedProps: { observe: true },
      };
      return this.agent().run(input);
    }));
  }

  async isRunning({ threadId }: AgentRunnerIsRunningRequest) {
    const status = await this.thread(threadId);
    return Boolean(status.activeRunId || status.executing);
  }

  async stop({ threadId, runId }: AgentRunnerStopRequest) {
    const response = await fetch(backend + "/api/chat/agui/threads/" + encodeURIComponent(threadId) + "/stop", {
      method: "POST",
      headers: { ...this.authHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ runId }),
      cache: "no-store",
    });
    if (!response.ok) throw new Error("停止任务失败（" + response.status + "）");
    const body = await response.json();
    if (body.code !== 0) throw new Error(body.message || "停止任务失败");
    return true;
  }
}

export async function handleCopilotRequest(request: Request) {
  const authHeaders: Record<string, string> = {};
  for (const name of ["cookie", "authorization"]) {
    const value = request.headers.get(name);
    if (value) authHeaders[name] = value;
  }
  const upstream = request.headers.get("traceparent")?.match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/);
  const validUpstream = upstream && !/^0+$/.test(upstream[1]) && !/^0+$/.test(upstream[2]);
  const traceId = validUpstream ? upstream[1] : crypto.randomUUID().replaceAll("-", "");
  const spanId = crypto.randomUUID().replaceAll("-", "").slice(0, 16);
  authHeaders.traceparent = ["00", traceId, spanId, validUpstream ? upstream[3] : "01"].join("-");
  const traceState = validUpstream ? request.headers.get("tracestate") : null;
  if (traceState) authHeaders.tracestate = traceState;

  // /info, /connect and /stop require authentication too. Never cache one user's agent or cookie globally.
  const authenticated = await fetch(backend + "/api/auth/me", { headers: authHeaders, cache: "no-store" })
    .catch(() => Response.json({ code: 50200, message: "无法连接聊天服务", data: null, traceId }, { status: 502 }));
  if (!authenticated.ok) return new Response(authenticated.body, {
    status: authenticated.status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "X-Trace-Id": traceId },
  });
  const runtime = new CopilotRuntime({
    agents: { default: new HttpAgent({ url: backend + "/api/chat/agui", headers: authHeaders }) },
    runner: new JavaAgentRunner(authHeaders),
  });
  const response = await createCopilotRuntimeHandler({ runtime, basePath: "/api/copilotkit" })(request);
  const headers = new Headers(response.headers);
  headers.set("X-Trace-Id", traceId);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

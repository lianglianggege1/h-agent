import assert from "node:assert/strict";
import { test } from "node:test";

process.env.COPILOTKIT_TELEMETRY_DISABLED = "true";
const { handleCopilotRequest } = await import("./copilot-runtime.ts");

const ok = (data) => new Response(JSON.stringify({ code: 0, message: "ok", data }), {
  headers: { "Content-Type": "application/json" },
});

function sse(threadId, runId) {
  const events = [
    { type: "RUN_STARTED", threadId, runId },
    { type: "MESSAGES_SNAPSHOT", messages: [{ id: "persisted-user", role: "user", content: "hello" }] },
    { type: "ACTIVITY_SNAPSHOT", messageId: "progress", activityType: "h-agent.progress", content: { status: "RUNNING", steps: [] } },
    { type: "TEXT_MESSAGE_START", messageId: "answer", role: "assistant" },
    { type: "TEXT_MESSAGE_CONTENT", messageId: "answer", delta: "answer from Java" },
    { type: "TEXT_MESSAGE_END", messageId: "answer" },
    { type: "RUN_FINISHED", threadId, runId },
  ];
  return new Response(events.map((event) => "data: " + JSON.stringify(event) + "\n\n").join(""), {
    headers: { "Content-Type": "text/event-stream" },
  });
}

function request(path, body) {
  return new Request("http://localhost/api/copilotkit" + path, {
    method: "POST", headers: { "Content-Type": "application/json", cookie: "access_token=alice", "x-user-id": "attacker" },
    body: JSON.stringify(body),
  });
}

test("Runtime authenticates discovery endpoints too", async (t) => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  globalThis.fetch = async () => new Response("unauthorized", { status: 401 });
  const response = await handleCopilotRequest(new Request("http://localhost/api/copilotkit/info"));
  assert.equal(response.status, 401);
});

test("official SDK relays Java events and submits only new user text with owned credentials", async (t) => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  let submitted;
  globalThis.fetch = async (url, init = {}) => {
    if (String(url).endsWith("/api/auth/me")) return ok({ userId: 1 });
    if (String(url).endsWith("/api/chat/agui")) {
      submitted = { input: JSON.parse(init.body), headers: new Headers(init.headers) };
      return sse(submitted.input.threadId, submitted.input.runId);
    }
    throw new Error("Unexpected fetch " + url);
  };
  const response = await handleCopilotRequest(request("/agent/default/run", {
    threadId: "root", runId: "run-1",
    messages: [
      { id: "old", role: "user", content: "old message" },
      { id: "card", role: "activity", activityType: "h-agent.progress", content: { status: "COMPLETED" } },
      { id: "new", role: "user", content: "hello" },
    ],
    tools: [], context: [], state: { fakeServerState: true },
    forwardedProps: { resources: [{ resourceId: "history-image", role: "REFERENCE", source: "HISTORY" }] },
  }));
  assert.equal(response.status, 200);
  const wire = await response.text();
  assert.ok(wire.includes("answer from Java"));
  assert.ok(wire.includes("ACTIVITY_SNAPSHOT"));
  assert.deepEqual(submitted.input.messages, [{ id: "new", role: "user", content: "hello" }]);
  assert.deepEqual(submitted.input.state, {});
  assert.deepEqual(submitted.input.forwardedProps.resources, [{ resourceId: "history-image", role: "REFERENCE", source: "HISTORY" }]);
  assert.equal(submitted.headers.get("cookie"), "access_token=alice");
  assert.equal(submitted.headers.get("x-user-id"), null);
});

test("reconnect observes the existing run instead of submitting another task", async (t) => {
  const original = globalThis.fetch;
  t.after(() => { globalThis.fetch = original; });
  let observed;
  globalThis.fetch = async (url, init = {}) => {
    if (String(url).endsWith("/api/auth/me")) return ok({ userId: 1 });
    if (String(url).endsWith("/api/chat/agui/threads/root")) return ok({ activeRunId: "existing-run" });
    if (String(url).endsWith("/api/chat/agui")) {
      observed = JSON.parse(init.body);
      return sse(observed.threadId, observed.runId);
    }
    throw new Error("Unexpected fetch " + url);
  };
  const response = await handleCopilotRequest(request("/agent/default/connect", {
    threadId: "root", runId: "observer", messages: [], tools: [], context: [], state: {}, forwardedProps: {},
  }));
  assert.equal(response.status, 200);
  await response.text();
  assert.equal(observed.runId, "existing-run");
  assert.deepEqual(observed.messages, []);
  assert.deepEqual(observed.forwardedProps, { observe: true });
});

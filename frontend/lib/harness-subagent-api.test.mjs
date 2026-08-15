import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildSubagentMessageRequest,
  getHarnessSubagents,
  observeHarnessSubagentEvents,
} from "./harness-subagent-api.ts";

test("harness collaboration API sends the actual Agent Session only", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (path, init) => {
    requests.push([path, init]);
    const data = [];
    return new Response(JSON.stringify({ code: 0, message: "ok", data }), { status: 200 });
  };

  try {
    await getHarnessSubagents("parent/1");
    assert.equal(requests[0][0], "/api/chat/sessions/parent%2F1/subagents");
    assert.deepEqual(buildSubagentMessageRequest({
      message: "补充来源",
      sessionId: "child-runtime-research",
      agentId: "harness-agent",
      resources: null,
    }), {
      message: "补充来源",
      sessionId: "child-runtime-research",
      promptId: null,
      agentId: "harness-agent",
      resources: null,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("child observer stream uses the actual child session and dispatches harness events", async () => {
  const originalFetch = globalThis.fetch;
  const requests = [];
  const childEvent = {
    schema: "harness.agent-event",
    schemaVersion: 3,
    runId: "child-stream",
    sequence: 1,
    eventId: "delta-1",
    eventType: "TEXT_BLOCK_DELTA",
    source: { scope: "SUBAGENT", path: "product-relay/child/1" },
    correlation: { replyId: "reply-1" },
    data: { agentSessionId: "child/1", delta: "第一段" },
  };
  globalThis.fetch = async (path, init) => {
    requests.push([path, init]);
    return new Response(
      `event: harness_event\ndata: ${JSON.stringify({ type: "harness_event", payload: childEvent })}\n\n`,
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );
  };

  try {
    const observed = [];
    await observeHarnessSubagentEvents("child/1", (event) => observed.push(event));
    assert.equal(requests[0][0], "/api/chat/agent-sessions/child%2F1/events");
    assert.equal(requests[0][1].method, "GET");
    assert.deepEqual(observed, [childEvent]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

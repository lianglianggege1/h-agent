import assert from "node:assert/strict";
import { test } from "node:test";

import {
  buildSubagentMessageRequest,
  getHarnessSubagents,
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

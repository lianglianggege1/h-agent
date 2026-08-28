import assert from "node:assert/strict";
import { test } from "node:test";
import {
  approvalDecisionPath,
  approvalModeOptions,
  getPendingApproval,
} from "./harness-approval.ts";

test("approval mode picker exposes every AgentScope permission mode", () => {
  assert.deepEqual(
    approvalModeOptions.map((item) => item.value),
    ["DEFAULT", "ACCEPT_EDITS", "EXPLORE", "DONT_ASK", "BYPASS"],
  );
});

test("approval endpoints encode user-controlled path identifiers", async () => {
  const originalFetch = globalThis.fetch;
  let requestedPath;
  globalThis.fetch = async (path) => {
    requestedPath = path;
    return new Response(JSON.stringify({ code: 0, message: "OK", data: null }), { status: 200 });
  };
  try {
    assert.equal(approvalDecisionPath("approval/1"), "/api/chat/approvals/approval%2F1/decision");
    assert.equal(await getPendingApproval("session/1"), null);
    assert.equal(requestedPath, "/api/chat/agent-sessions/session%2F1/pending-approval");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

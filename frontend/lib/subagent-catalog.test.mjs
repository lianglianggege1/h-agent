import assert from "node:assert/strict";
import { test } from "node:test";

import {
  createSubagentDefinition,
  extractSubagentError,
  isValidSubagentAgentId,
  publishSubagentDefinition,
  restoreSubagentDefinition,
  saveSubagentDraft,
  setSubagentEnabled,
  SUBAGENT_TEMPLATE,
  validateSubagentMarkdown,
} from "./subagent-catalog.ts";

function withMockFetch(responder) {
  const originalFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (path, init) => {
    requests.push({ path, init });
    return responder({ path, init });
  };
  return {
    requests,
    restore() {
      globalThis.fetch = originalFetch;
    },
  };
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status });
}

test("subagent catalog create posts agentId and markdown", async () => {
  const mock = withMockFetch(() =>
    jsonResponse({ code: 0, message: "ok", data: { agentId: "my-reviewer", definitionId: 1, revision: 1, issues: [] } }),
  );

  try {
    const result = await createSubagentDefinition({ agentId: "my-reviewer", markdown: "---\n..." });
    assert.equal(result.revision, 1);
    assert.equal(mock.requests[0].path, "/api/me/subagents");
    assert.equal(mock.requests[0].init.method, "POST");
    assert.deepEqual(JSON.parse(mock.requests[0].init.body), {
      agentId: "my-reviewer",
      markdown: "---\n...",
    });
  } finally {
    mock.restore();
  }
});

test("subagent catalog save draft sends expected revision for optimistic concurrency", async () => {
  const mock = withMockFetch(() =>
    jsonResponse({ code: 0, message: "ok", data: { agentId: "my-reviewer", definitionId: 1, revision: 5, issues: [] } }),
  );

  try {
    const result = await saveSubagentDraft("my-reviewer", { expectedRevision: 4, markdown: "x" });
    assert.equal(result.revision, 5);
    assert.equal(mock.requests[0].path, "/api/me/subagents/my-reviewer/draft");
    assert.equal(mock.requests[0].init.method, "PUT");
    assert.deepEqual(JSON.parse(mock.requests[0].init.body), {
      expectedRevision: 4,
      markdown: "x",
    });
  } finally {
    mock.restore();
  }
});

test("subagent catalog publishes the saved revision only", async () => {
  const mock = withMockFetch(() =>
    jsonResponse({
      code: 0,
      message: "ok",
      data: { agentId: "my-reviewer", definitionId: 1, version: 2, contentHash: "abc", enabled: true, revision: 5, compiled: null },
    }),
  );

  try {
    const result = await publishSubagentDefinition("my-reviewer", 5);
    assert.equal(result.version, 2);
    assert.equal(mock.requests[0].path, "/api/me/subagents/my-reviewer/publish");
    assert.equal(mock.requests[0].init.method, "POST");
    assert.deepEqual(JSON.parse(mock.requests[0].init.body), { expectedRevision: 5 });
  } finally {
    mock.restore();
  }
});

test("subagent catalog validate posts markdown without agentId", async () => {
  const mock = withMockFetch(() => jsonResponse({ code: 0, message: "ok", data: { issues: [] } }));

  try {
    await validateSubagentMarkdown("# draft");
    assert.equal(mock.requests[0].path, "/api/me/subagents/validate");
    assert.deepEqual(JSON.parse(mock.requests[0].init.body), { markdown: "# draft" });
  } finally {
    mock.restore();
  }
});

test("subagent catalog enable and restore use the documented verbs", async () => {
  const detail = {
    agentId: "my-reviewer",
    definitionId: 1,
    source: "USER",
    currentVersion: 1,
    currentMarkdown: null,
    currentContentHash: "abc",
    enabled: true,
    deleted: false,
    draftRevision: 3,
    draftMarkdown: null,
    draftIssues: [],
    createdAt: null,
    updatedAt: null,
  };
  const mock = withMockFetch(() => jsonResponse({ code: 0, message: "ok", data: detail }));

  try {
    await setSubagentEnabled("my-reviewer", false);
    await restoreSubagentDefinition("my-reviewer");
    assert.equal(mock.requests[0].path, "/api/me/subagents/my-reviewer/enabled");
    assert.equal(mock.requests[0].init.method, "PUT");
    assert.deepEqual(JSON.parse(mock.requests[0].init.body), { enabled: false });
    assert.equal(mock.requests[1].path, "/api/me/subagents/my-reviewer/restore");
    assert.equal(mock.requests[1].init.method, "POST");
  } finally {
    mock.restore();
  }
});

test("subagent catalog error extraction surfaces errorCode and issues", async () => {
  const mock = withMockFetch(() =>
    jsonResponse(
      {
        code: 409,
        message: "服务器草稿已变化",
        data: { errorCode: "DRAFT_REVISION_CONFLICT", issues: [] },
      },
      409,
    ),
  );

  try {
    const caught = await saveSubagentDraft("my-reviewer", {
      expectedRevision: 4,
      markdown: "x",
    }).catch((error) => error);
    const parsed = extractSubagentError(caught);
    assert.equal(parsed.errorCode, "DRAFT_REVISION_CONFLICT");
    assert.deepEqual(parsed.issues, []);
  } finally {
    mock.restore();
  }
});

test("subagent catalog error extraction returns empty issues for non-api errors", () => {
  const parsed = extractSubagentError(new Error("网络错误"));
  assert.equal(parsed.errorCode, null);
  assert.deepEqual(parsed.issues, []);
});

test("subagent agentId rules mirror backend kebab-case constraints", () => {
  assert.equal(isValidSubagentAgentId("my-reviewer"), true);
  assert.equal(isValidSubagentAgentId("researcher2"), true);
  assert.equal(isValidSubagentAgentId("My-Reviewer"), false);
  assert.equal(isValidSubagentAgentId("-reviewer"), false);
  assert.equal(isValidSubagentAgentId("my--reviewer"), false);
  assert.equal(isValidSubagentAgentId("a".repeat(64)), false);
});

test("subagent template contains required front matter fields and body", () => {
  assert.ok(SUBAGENT_TEMPLATE.startsWith("---\n"));
  assert.ok(SUBAGENT_TEMPLATE.includes("display_name:"));
  assert.ok(SUBAGENT_TEMPLATE.includes("description:"));
  assert.ok(SUBAGENT_TEMPLATE.includes("mode: subagent"));
  assert.ok(SUBAGENT_TEMPLATE.trimEnd().endsWith("。"));
});

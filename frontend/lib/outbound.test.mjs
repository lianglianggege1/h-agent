import assert from "node:assert/strict";
import { test } from "node:test";
import { identityForDraft, parseContactRows } from "./outbound.ts";

test("parses comma and tab separated contact rows", () => {
  assert.deepEqual(parseContactRows("1001,张三,已书面授权\n1002\t李四\t合同第 3 条"), [
    { phone: "1001", name: "张三", consentBasis: "已书面授权" },
    { phone: "1002", name: "李四", consentBasis: "合同第 3 条" },
  ]);
});

test("keeps request id for retries and changes it when draft changes", () => {
  const draft = { name: "回访", agentId: "harness-agent", communicationGoal: "确认", contactIds: [2, 1] };
  const first = identityForDraft(null, draft, () => "request-1");
  const retry = identityForDraft(first, { ...draft, contactIds: [1, 2] }, () => "request-2");
  const changed = identityForDraft(first, { ...draft, communicationGoal: "更新" }, () => "request-3");
  assert.equal(retry.requestId, "request-1");
  assert.equal(changed.requestId, "request-3");
});

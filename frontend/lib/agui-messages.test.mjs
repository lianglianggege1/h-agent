import assert from "node:assert/strict";
import { test } from "node:test";
import { orderProductMessages, productHistoryMessages } from "./agui-messages.ts";

test("restored progress and proposal cards remain attached to their own turn", () => {
  const messages = [
    { id: "u1", role: "user", content: "first" }, { id: "a1", role: "assistant", content: "first answer" },
    { id: "u2", role: "user", content: "second" }, { id: "a2", role: "assistant", content: "second answer" },
    { id: "progress", role: "activity", activityType: "h-agent.progress", content: { anchorMessageId: "u1" } },
    { id: "proposal", role: "activity", activityType: "h-agent.automation", content: { proposal: { anchorMessageId: "a1", anchorPlacement: "AFTER" } } },
  ];
  assert.deepEqual(orderProductMessages(messages).map((message) => message.id), ["u1", "progress", "a1", "proposal", "u2", "a2"]);
});

test("cards outside the loaded page reappear when their owning message is prepended", () => {
  const card = { id: "old-card", role: "activity", activityType: "h-agent.progress", content: { anchorMessageId: "old-answer", anchorPlacement: "BEFORE" } };
  const current = { id: "current", role: "assistant", content: "current" };
  assert.deepEqual(orderProductMessages([current, card]), [current]);
  const old = { id: "old-answer", role: "assistant", content: "old" };
  assert.deepEqual(orderProductMessages([old, current, card]).map((message) => message.id), ["old-card", "old-answer", "current"]);
});

const historicalMessage = (id, messageType, content = "", resources = []) => ({
  id, messageType, content, resources, role: messageType === "USER" ? "user" : "assistant", createdAt: "2026-10-23T00:00:00",
});

test("restored cards follow the original image, reasoning, answer order across multiple turns", () => {
  const history = [
    historicalMessage("u1", "USER", "first"), historicalMessage("r1", "REASONING", "first thinking"),
    historicalMessage("i1", "IMAGE", "", [{ id: "image-1" }]), historicalMessage("a1", "AI", "first answer"),
    historicalMessage("u2", "USER", "second"), historicalMessage("r2", "REASONING", "second thinking"),
    historicalMessage("a2", "AI", "second answer"),
  ];
  const projected = productHistoryMessages(history);
  // Reconnect emits text first, then the activity snapshots; arrival order must not change history.
  const wireOrder = [...projected.filter((item) => item.role !== "activity"), ...projected.filter((item) => item.role === "activity")];
  assert.deepEqual(orderProductMessages(wireOrder).map((item) => item.id),
    ["u1", "i1", "resource-i1", "reasoning-r1", "a1", "u2", "reasoning-r2", "a2"]);
});

test("loading an older page retains its reasoning and resources beside the older answer", () => {
  const older = productHistoryMessages([
    historicalMessage("old-user", "USER", "old"), historicalMessage("old-reason", "REASONING", "thinking"),
    historicalMessage("old-image", "IMAGE", "", [{ id: "old-resource" }]), historicalMessage("old-answer", "AI", "answer"),
  ]);
  const current = productHistoryMessages([historicalMessage("current-user", "USER", "current"), historicalMessage("current-answer", "AI", "answer")]);
  assert.deepEqual(orderProductMessages([...older, ...current]).map((item) => item.id),
    ["old-user", "old-image", "resource-old-image", "reasoning-old-reason", "old-answer", "current-user", "current-answer"]);
});

test("standalone reasoning and parent assignments stay in the surrounding historical turn", () => {
  const projected = productHistoryMessages([
    { ...historicalMessage("assignment", "SYSTEM", "delegated task"), role: "system" },
    historicalMessage("answer", "AI", "answer"), historicalMessage("reason", "REASONING", "unfinished thinking"),
  ]);
  assert.deepEqual(orderProductMessages(projected).map((item) => item.id), ["assignment-assignment", "answer", "reasoning-reason"]);
});

test("a page boundary between reasoning, image and answer keeps the original ordering", () => {
  const following = [historicalMessage("image", "IMAGE", "", [{ id: "resource" }]), historicalMessage("answer", "AI", "answer")];
  const older = productHistoryMessages([
    historicalMessage("user", "USER", "request"), historicalMessage("reason", "REASONING", "thinking"),
  ], following);
  const current = productHistoryMessages(following);
  assert.deepEqual(orderProductMessages([...older, ...current]).map((item) => item.id),
    ["user", "image", "resource-image", "reasoning-reason", "answer"]);
});

test("a turn has one process across live tools, history recovery and approval continuation", () => {
  const history = productHistoryMessages([
    historicalMessage("user", "USER", "request"), historicalMessage("reason", "REASONING", "thinking"),
    historicalMessage("answer", "AI", "answer"),
  ]);
  const before = { id: "p1", role: "activity", activityType: "h-agent.progress", content: {
    runId: "r1", status: "RESUMED", anchorMessageId: "user", startedAt: 1000, processEndedAt: 3000,
    entries: [{ id: "reasoning-0", type: "reasoning", chunks: ["thinking"] }, { id: "tool-call", type: "step", stepId: "tool-call" }],
    steps: [{ id: "tool-call", kind: "tool", name: "search", status: "completed" }],
  } };
  const after = { id: "p2", role: "activity", activityType: "h-agent.progress", content: {
    runId: "r2", status: "COMPLETED", anchorMessageId: "user", startedAt: 5000, processEndedAt: 9000,
    entries: [{ id: "reasoning-0", type: "reasoning", chunks: ["confirmed"] }], steps: [],
  } };
  const tool = { id: "call-message", role: "assistant", toolCalls: [{ id: "call", type: "function", function: { name: "search", arguments: "{}" } }] };
  const toolResult = { id: "call-result", role: "tool", toolCallId: "call", content: "result" };
  const projected = orderProductMessages([...history, before, tool, toolResult, after]);
  assert.deepEqual(projected.map((message) => message.id), ["user", "p1", "answer"]);
  const process = projected[1].content;
  assert.equal(process.status, "COMPLETED");
  assert.equal(process.answerStarted, true);
  assert.equal(process.runId, "r1", "Continuation retains the same reader preference identity");
  assert.equal(process.steps.length, 1);
  assert.deepEqual(process.entries.filter((entry) => entry.type === "reasoning").flatMap((entry) => entry.chunks), ["thinking", "confirmed"]);
  assert.equal(new Set(process.entries.map((entry) => entry.id)).size, 3, "Block identities remain distinct across runs");
  assert.equal(before.content.status, "RESUMED", "SDK state must not be mutated by a view transform");
});

test("a resource caption does not close a process before actual answer content", () => {
  const process = { id: "process", role: "activity", activityType: "h-agent.progress", content: {
    runId: "run", status: "RUNNING", anchorMessageId: "user", answerStarted: false,
    entries: [{ id: "reasoning", type: "reasoning", chunks: ["整理中"] }], steps: [],
  } };
  const projected = orderProductMessages([
    { id: "user", role: "user", content: "生成图片并解释" },
    { id: "image", role: "assistant", content: "图片已生成" }, process,
  ]);
  assert.equal(projected.find((message) => message.id === "process").content.answerStarted, false);
});

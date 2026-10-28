import assert from "node:assert/strict";
import { test } from "node:test";
import { pendingMediaMessageIds, mergeProductMessageUpdates } from "./agui-media-updates.ts";
import { productHistoryMessages } from "./agui-messages.ts";

const image = (status, extras = {}) => ({ id: "image", role: "assistant", messageType: "IMAGE", content: "生成中",
  payload: { status }, resources: [], createdAt: "2026-10-27T00:00:00", ...extras });

test("only unfinished generated media needs a request; terminal, uploaded and already delivered resources stop polling", () => {
  assert.deepEqual(pendingMediaMessageIds(productHistoryMessages([image("IN_PROGRESS")])), ["image"]);
  for (const status of ["READY", "SUCCEEDED", "FAILED", "CANCELLED", "SUBMISSION_UNKNOWN", null]) {
    assert.deepEqual(pendingMediaMessageIds(productHistoryMessages([image(status)])), []);
  }
  assert.deepEqual(pendingMediaMessageIds(productHistoryMessages([image("PENDING", { role: "user", messageType: "USER" })])), []);
  assert.deepEqual(pendingMediaMessageIds(productHistoryMessages([image("PENDING", { resources: [{ id: "ready-image", type: "IMAGE" }] })])), []);
  assert.deepEqual(pendingMediaMessageIds([
    ...productHistoryMessages([image("PENDING")]), ...productHistoryMessages([image("READY")]),
  ]), []);
});

test("identical media response preserves the message array and every object", () => {
  const pending = image("PENDING");
  const messages = productHistoryMessages([pending]);
  assert.equal(mergeProductMessageUpdates(messages, [structuredClone(pending)]), messages);
  assert.equal(mergeProductMessageUpdates(messages, [Object.fromEntries(Object.entries(pending).reverse())]), messages);
});

test("completion patches its text and card while preserving unrelated content and SDK process state", () => {
  const user = { id: "user", role: "user", content: "生成图片" };
  const progress = { id: "progress", role: "activity", activityType: "h-agent.progress", content: { status: "COMPLETED", entries: [] } };
  const messages = [user, progress, ...productHistoryMessages([image("PENDING")])];
  const next = mergeProductMessageUpdates(messages, [image("READY", { content: "图片已生成", resources: [{ id: "result", type: "IMAGE" }] })]);
  assert.equal(next[0], user);
  assert.equal(next[1], progress);
  assert.equal(next[2].content, "图片已生成");
  assert.equal(next[3].content.message.resources[0].id, "result");
  assert.deepEqual(pendingMediaMessageIds(next), []);
});

test("published messages append once without replacing current tool or progress messages", () => {
  const tool = { id: "tool", role: "tool", toolCallId: "call", content: "result" };
  const published = { id: "new", role: "assistant", messageType: "AI", content: "订单处理完成", createdAt: "2026-10-27T00:00:00" };
  const merged = mergeProductMessageUpdates([tool], [published]);
  assert.equal(merged[0], tool);
  assert.deepEqual(merged.map((message) => message.id), ["tool", "new"]);
  assert.equal(mergeProductMessageUpdates(merged, [published]), merged);
});

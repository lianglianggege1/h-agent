import assert from "node:assert/strict";
import { test } from "node:test";
import { orderProductMessages } from "./agui-messages.ts";

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

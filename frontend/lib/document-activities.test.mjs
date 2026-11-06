import assert from "node:assert/strict";
import { test } from "node:test";
import { mergeDocumentTaskActivities } from "./document-activities.ts";
import { orderProductMessages } from "./agui-messages.ts";

const task = {
  taskId: "doc",
  sessionId: "chat",
  anchorMessageId: "u1",
  status: "OCR_RUNNING",
  revision: 1,
};
const messages = [
  { id: "u1", role: "user", content: "识别图片" },
  { id: "a1", role: "assistant", content: "处理中" },
  { id: "u2", role: "user", content: "下一题" },
];
test("document review follows its original upload turn instead of the thread header", () => {
  const next = mergeDocumentTaskActivities(messages, [task]);
  assert.deepEqual(
    orderProductMessages(next).map((message) => message.id),
    ["u1", "document-doc", "a1", "u2"],
  );
  assert.equal(mergeDocumentTaskActivities(next, [{ ...task }]), next);
  const updated = mergeDocumentTaskActivities(next, [
    { ...task, status: "WAITING_CONFIRMATION", revision: 2 },
  ]);
  assert.equal(
    updated.filter((message) => message.id === "document-doc").length,
    1,
  );
  assert.equal(
    updated.find((message) => message.id === "document-doc").content.task
      .status,
    "WAITING_CONFIRMATION",
  );
  assert.deepEqual(
    mergeDocumentTaskActivities(updated, [{ ...task, status: "SUPERSEDED" }]),
    messages,
  );
});
test("reconnect and older history preserve the document card's owning message", () => {
  const current = [messages[2]];
  const next = mergeDocumentTaskActivities(current, [task]);
  assert.deepEqual(orderProductMessages(next), current);
  assert.deepEqual(
    orderProductMessages([messages[0], ...next]).map((message) => message.id),
    ["u1", "document-doc", "u2"],
  );
});

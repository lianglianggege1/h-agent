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
test("completed generation yields to its delivered resource card", () => {
  const generated = { ...task, kind: "GENERATE", status: "COMPLETED", outputResourceId: "out" };
  const pendingView = mergeDocumentTaskActivities(messages, [generated]);
  assert.ok(pendingView.some((message) => message.id === "document-doc"));
  const delivered = {
    id: "resource-file", role: "activity", activityType: "h-agent.resource",
    content: { message: { role: "assistant", resources: [{ id: "out" }] } },
  };
  const next = mergeDocumentTaskActivities([...pendingView, delivered], [generated]);
  assert.equal(next.some((message) => message.id === "document-doc"), false);
  assert.ok(next.includes(delivered));
  // Another resource, even with the same name, cannot settle this generation's card.
  assert.ok(mergeDocumentTaskActivities([...messages, delivered], [
    { ...generated, outputResourceId: "other" },
  ]).some((message) => message.id === "document-doc"));
});
test("one generated document has one file card even when an old turn sent a copy", () => {
  const generated = { ...task, kind: "GENERATE", status: "COMPLETED",
    fileName: "报告.md", outputResourceId: "canonical" };
  const file = (id, resourceId) => [
    { id, role: "assistant", content: "文件已发送" },
    { id: `resource-${id}`, role: "activity", activityType: "h-agent.resource",
      content: { message: { id, role: "assistant", messageType: "FILE",
        resources: [{ id: resourceId, fileName: "报告.md" }] } } },
  ];
  const history = [messages[0], ...file("f1", "canonical"), ...file("f2", "copy"),
    messages[2], ...file("f3", "later-turn")];
  const next = mergeDocumentTaskActivities(history, [generated]);
  assert.deepEqual(next.map((message) => message.id),
    ["u1", "f1", "resource-f1", "u2", "f3", "resource-f3"]);
  assert.equal(history.length, 8, "the stored history must not be mutated");
});
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

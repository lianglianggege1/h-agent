import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildReplyContent, buildTextContent, kindLabel, linkifyMxc, mentionIdsFor, mentionTrigger,
  senderLabel, stripReplyFallback, toMessageView, toProgressView,
} from "./team-room.ts";

const config = {
  homeserverUrl: "http://localhost:8008",
  roomId: "!room:matrix.local",
  managerUserId: "@manager:matrix.local",
  workerUserId: "@worker:matrix.local",
  springWorkerUserId: "@spring-worker:matrix.local",
};

test("parses text events with mentions and team metadata", () => {
  const view = toMessageView({
    event_id: "$1",
    sender: "@manager:matrix.local",
    origin_server_ts: 123,
    type: "m.room.message",
    content: {
      msgtype: "m.text",
      body: "[委派 task-1] 检查代码",
      "m.mentions": { user_ids: ["@worker:matrix.local"] },
      "com.h.team": { kind: "delegate", taskId: "task-1" },
    },
  });
  assert.equal(view.id, "$1");
  assert.equal(view.kind, "delegate");
  assert.equal(view.taskId, "task-1");
  assert.deepEqual(view.mentions, ["@worker:matrix.local"]);
});

test("ignores non-text and malformed events", () => {
  assert.equal(toMessageView({ type: "m.room.member" }), null);
  assert.equal(toMessageView({ type: "m.room.message", event_id: "$2", sender: "@a:b", content: { msgtype: "m.image", body: "x" } }), null);
  assert.equal(toMessageView({ type: "m.room.message", content: { msgtype: "m.text", body: "x" } }), null);
});

test("human messages carry no team metadata", () => {
  const view = toMessageView({
    event_id: "$3", sender: "@huajiang:matrix.local", type: "m.room.message",
    content: { msgtype: "m.text", body: "你好" },
  });
  assert.equal(view.kind, null);
  assert.deepEqual(view.mentions, []);
});

test("builds structured mentions only when selected", () => {
  assert.deepEqual(buildTextContent("你好", []), { msgtype: "m.text", body: "你好" });
  assert.deepEqual(buildTextContent("@Manager 你好", ["@manager:matrix.local"]), {
    msgtype: "m.text",
    body: "@Manager 你好",
    "m.mentions": { user_ids: ["@manager:matrix.local"] },
  });
});

test("labels senders and kinds", () => {
  assert.equal(senderLabel("@huajiang:matrix.local", "@huajiang:matrix.local", config), "自己");
  assert.equal(senderLabel("@manager:matrix.local", "@huajiang:matrix.local", config), "Manager");
  assert.equal(senderLabel("@worker:matrix.local", "@huajiang:matrix.local", config), "Worker");
  assert.equal(senderLabel("@spring-worker:matrix.local", "@huajiang:matrix.local", config), "SpringWorker");
  assert.equal(senderLabel("@other:matrix.local", "@huajiang:matrix.local", config), "other");
  assert.equal(kindLabel("report"), "回报");
  assert.equal(kindLabel(null), null);
});

test("mention trigger tracks the @token before the caret", () => {
  assert.deepEqual(mentionTrigger("@", 1), { start: 0, query: "" });
  assert.deepEqual(mentionTrigger("hi @Man", 7), { start: 3, query: "Man" });
  assert.equal(mentionTrigger("hi @Manager ", 12), null);
  assert.equal(mentionTrigger("mail@example.com", 16), null);
});

test("mention ids derive from the message text", () => {
  assert.deepEqual(mentionIdsFor("@Worker 只回复 OK", config), ["@worker:matrix.local"]);
  assert.deepEqual(mentionIdsFor("@SpringWorker 只回复 OK", config), ["@spring-worker:matrix.local"]);
  assert.deepEqual(mentionIdsFor("@manager 和 @Worker 协作", config), ["@manager:matrix.local", "@worker:matrix.local"]);
  assert.deepEqual(mentionIdsFor("@worker 和 @springworker 协作", config), ["@worker:matrix.local", "@spring-worker:matrix.local"]);
  assert.deepEqual(mentionIdsFor("没有点名", config), []);
  assert.deepEqual(mentionIdsFor("@workerx 不是点名", config), []);
});

test("reply fallback is stripped from display body but kept for agents", () => {
  const body = "> <@worker:matrix.local> 原消息第一行\n> 第二行\n\n我的回复";
  const parsed = stripReplyFallback(body);
  assert.equal(parsed.body, "我的回复");
  assert.deepEqual(parsed.fallback, { sender: "@worker:matrix.local", text: "原消息第一行\n第二行" });
  assert.deepEqual(stripReplyFallback("普通消息"), { body: "普通消息", fallback: null });
  const content = buildReplyContent("我的回复", [], { eventId: "$9", sender: "@worker:matrix.local", body: "原消息第一行", msgtype: "m.text" });
  assert.equal(content.body, body.replace("原消息第一行\n> 第二行", "原消息第一行"));
  assert.deepEqual(content["m.relates_to"], { "m.in_reply_to": { event_id: "$9" } });
  const view = toMessageView({
    event_id: "$10", sender: "@huajiang:matrix.local", type: "m.room.message",
    content: { msgtype: "m.text", body, "m.relates_to": { "m.in_reply_to": { event_id: "$9" } } },
  });
  assert.equal(view.body, "我的回复");
  assert.equal(view.replyTo, "$9");
  assert.equal(view.replyFallback.sender, "@worker:matrix.local");
});

test("mxc references become markdown links unless already linked or in code", () => {
  assert.equal(
    linkifyMxc("结果见 mxc://matrix.local/AbC123 谢谢"),
    "结果见 [AbC123](mxc://matrix.local/AbC123) 谢谢",
  );
  assert.equal(linkifyMxc("[文件](mxc://matrix.local/AbC123)"), "[文件](mxc://matrix.local/AbC123)");
  assert.equal(linkifyMxc("`mxc://matrix.local/AbC123`"), "`mxc://matrix.local/AbC123`");
});

test("progress snapshots parse into run views", () => {
  const view = toProgressView({
    event_id: "$p1", sender: "@worker:matrix.local", origin_server_ts: 5, type: "com.h.agent.progress",
    content: {
      runId: "run-1", status: "RUNNING", answerStarted: false, startedAt: 1,
      steps: [{ id: "tool-1", name: "web_search", status: "RUNNING", kind: "tool" }],
      entries: [{ id: "e1", type: "reasoning", chunks: ["想一想"] }],
    },
  });
  assert.equal(view.runId, "run-1");
  assert.equal(view.steps.length, 1);
  assert.equal(view.entries[0].type, "reasoning");
  assert.equal(toProgressView({ event_id: "$p2", sender: "@w:b", type: "m.room.message", content: {} }), null);
});
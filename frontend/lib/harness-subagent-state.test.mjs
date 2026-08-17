import assert from "node:assert/strict";
import { test } from "node:test";

import {
  applyHarnessEvent,
  createHarnessUiState,
  replaceHarnessSubagents,
  orderedHarnessSubagents,
  canSubmitSubagentStatus,
  applyHarnessTranscriptEvent,
  harnessTranscriptSessionId,
  isHarnessTranscriptStreamingEvent,
  mergePersistedHarnessMessages,
} from "./harness-subagent-state.ts";

test("replaceHarnessSubagents replaces latest state and keeps stable display order", () => {
  const initial = replaceHarnessSubagents(createHarnessUiState(), [
      {
        sessionId: "session-summary",
        parentSessionId: "session-root",
        displayName: "结论汇总",
        assignment: "汇总结论",
        status: "AVAILABLE",
        displayOrder: 2,
        updatedAt: "2026-08-11T10:00:00",
      },
      {
        sessionId: "session-research",
        parentSessionId: "session-root",
        displayName: "资料收集",
        assignment: "收集资料",
        status: "RUNNING",
        displayOrder: 0,
        updatedAt: "2026-08-11T09:59:00",
      },
  ]);

  assert.deepEqual(
    orderedHarnessSubagents(initial).map((subagent) => subagent.sessionId),
    ["session-research", "session-summary"],
  );
});

test("subagent exposure immediately seeds the full parent assignment", () => {
  const event = {
    schema: "harness.agent-event",
    schemaVersion: 3,
    runId: "58",
    sequence: 4,
    eventId: "exposed-live",
    eventType: "SUBAGENT_EXPOSED",
    kind: "SUBAGENT",
    phase: "EVENT",
    source: { scope: "PARENT", path: null },
    correlation: {},
    data: { sessionId: "child-live" },
    projection: {
      subagent: {
        sessionId: "child-live",
        parentSessionId: "parent-live",
        displayName: "实时验证",
        assignment: "在任务执行期间立即展示这段完整委托",
        status: "AVAILABLE",
        displayOrder: 0,
        updatedAt: "2026-08-14T09:38:47",
      },
    },
  };

  assert.equal(harnessTranscriptSessionId(event), "child-live");
  assert.deepEqual(applyHarnessTranscriptEvent([], event), [{
    id: "assignment-child-live",
    role: "system",
    messageType: "SYSTEM",
    content: "在任务执行期间立即展示这段完整委托",
  }]);
});

test("child reasoning and answer deltas stream into its own transcript", () => {
  const base = {
    schema: "harness.agent-event",
    schemaVersion: 3,
    runId: "58",
    kind: "MODEL_OUTPUT",
    phase: "DELTA",
    source: { scope: "SUBAGENT", path: "parent/general-purpose" },
    projection: null,
  };
  const start = {
    ...base,
    sequence: 5,
    eventId: "child-start",
    eventType: "AGENT_START",
    correlation: { replyId: "reply-live" },
    data: { agentSessionId: "child-live", sessionId: "child-live" },
  };
  const thinking = {
    ...base,
    sequence: 6,
    eventId: "child-thinking",
    eventType: "THINKING_BLOCK_DELTA",
    correlation: { replyId: "reply-live", blockId: "thinking-1" },
    data: { agentSessionId: "child-live", delta: "正在拆解问题" },
  };
  const text = {
    ...base,
    sequence: 7,
    eventId: "child-text",
    eventType: "TEXT_BLOCK_DELTA",
    correlation: { replyId: "reply-live", blockId: "text-1" },
    data: { agentSessionId: "child-live", delta: "第一段结果" },
  };

  let messages = applyHarnessTranscriptEvent([], start);
  messages = applyHarnessTranscriptEvent(messages, thinking);
  messages = applyHarnessTranscriptEvent(messages, text);

  assert.equal(isHarnessTranscriptStreamingEvent(start), true);
  assert.equal(harnessTranscriptSessionId(text), "child-live");
  assert.deepEqual(messages.map((message) => [message.messageType, message.content]), [
    ["REASONING", "正在拆解问题"],
    ["AI", "第一段结果"],
  ]);
});

test("child start replaces an exposure label with the lifecycle assignment", () => {
  const existing = [{
    id: "assignment-child-race",
    role: "system",
    messageType: "SYSTEM",
    content: "实时协作者",
  }];
  const start = {
    schema: "harness.agent-event",
    schemaVersion: 3,
    runId: "59",
    sequence: 8,
    eventId: "child-start-race",
    eventType: "AGENT_START",
    kind: "AGENT_STATUS",
    phase: "START",
    source: { scope: "SUBAGENT", path: "product-relay/child-race" },
    correlation: { replyId: "reply-race" },
    data: { agentSessionId: "child-race" },
    projection: {
      subagent: {
        sessionId: "child-race",
        parentSessionId: "parent-race",
        displayName: "实时协作者",
        assignment: "运行开始时必须显示的完整父委托",
        status: "RUNNING",
        displayOrder: 0,
        updatedAt: "2026-08-14T10:00:00",
      },
    },
  };

  const messages = applyHarnessTranscriptEvent(existing, start);

  assert.equal(messages[0].content, "运行开始时必须显示的完整父委托");
  assert.deepEqual(messages.slice(1).map((message) => message.messageType), ["REASONING", "AI"]);
});

test("history refresh keeps live child deltas until the final assistant is persisted", () => {
  const persistedAssignment = [{
    id: "db-assignment",
    role: "system",
    messageType: "SYSTEM",
    content: "完整委托",
  }];
  const live = [
    { id: "runtime-reasoning-reply-1", role: "assistant", messageType: "REASONING", content: "思考中" },
    { id: "runtime-assistant-reply-1", role: "assistant", messageType: "AI", content: "已输出一半" },
  ];

  assert.deepEqual(
    mergePersistedHarnessMessages(persistedAssignment, live).map((message) => message.id),
    ["db-assignment", "runtime-reasoning-reply-1", "runtime-assistant-reply-1"],
  );

  const persistedFinal = [...persistedAssignment, {
    id: "db-assistant",
    role: "assistant",
    messageType: "AI",
    content: "完整回答",
  }];
  assert.deepEqual(
    mergePersistedHarnessMessages(persistedFinal, live).map((message) => message.id),
    ["db-assignment", "db-assistant"],
  );
  assert.deepEqual(
    mergePersistedHarnessMessages(persistedFinal, live, true).map((message) => message.id),
    ["db-assignment", "runtime-reasoning-reply-1", "runtime-assistant-reply-1"],
  );
});

test("history refresh keeps failed runtime reasoning when persisted replies belong to older turns", () => {
  const persisted = [
    { id: "db-assignment", role: "system", messageType: "SYSTEM", content: "委托" },
    { id: "db-old-answer", role: "assistant", messageType: "AI", content: "旧回答" },
  ];
  const current = [
    ...persisted,
    {
      id: "runtime-reasoning-reply-failed",
      role: "assistant",
      messageType: "REASONING",
      content: "失败前已经完成的思考",
    },
    {
      id: "runtime-assistant-reply-failed",
      role: "assistant",
      messageType: "AI",
      content: "",
    },
  ];

  assert.deepEqual(
    mergePersistedHarnessMessages(persisted, current).map((message) => message.id),
    [
      "db-assignment",
      "db-old-answer",
      "runtime-reasoning-reply-failed",
      "runtime-assistant-reply-failed",
    ],
  );
});

test("history refresh replaces failed runtime reasoning after that reasoning is persisted", () => {
  const stableHistory = [
    { id: "db-assignment", role: "system", messageType: "SYSTEM", content: "委托" },
    { id: "db-old-answer", role: "assistant", messageType: "AI", content: "旧回答" },
  ];
  const persisted = [
    ...stableHistory,
    {
      id: "db-failed-reasoning",
      role: "assistant",
      messageType: "REASONING",
      content: "失败前已经完成的思考",
    },
  ];
  const current = [
    ...stableHistory,
    {
      id: "runtime-reasoning-reply-failed",
      role: "assistant",
      messageType: "REASONING",
      content: "失败前已经完成的思考",
    },
    {
      id: "runtime-assistant-reply-failed",
      role: "assistant",
      messageType: "AI",
      content: "",
    },
  ];

  assert.deepEqual(
    mergePersistedHarnessMessages(persisted, current).map((message) => message.id),
    ["db-assignment", "db-old-answer", "db-failed-reasoning"],
  );
});

test("history refresh preserves a direct follow-up placeholder after older persisted replies", () => {
  const persisted = [
    { id: "db-assignment", role: "system", messageType: "SYSTEM", content: "委托" },
    { id: "db-old-user", role: "user", messageType: "USER", content: "旧追问" },
    { id: "db-old-answer", role: "assistant", messageType: "AI", content: "旧回答" },
  ];
  const current = [
    ...persisted,
    { id: "reasoning-42", role: "assistant", messageType: "REASONING", content: "" },
    { id: "assistant-42", role: "assistant", messageType: "AI", content: "正在播放" },
  ];

  assert.deepEqual(
    mergePersistedHarnessMessages(persisted, current).map((message) => message.id),
    ["db-assignment", "db-old-user", "db-old-answer", "reasoning-42", "assistant-42"],
  );
});

test("only non-running subagent states accept follow-ups", () => {
  assert.equal(canSubmitSubagentStatus("RUNNING"), false);
  assert.equal(canSubmitSubagentStatus("AVAILABLE"), true);
  assert.equal(canSubmitSubagentStatus("COMPLETED"), true);
  assert.equal(canSubmitSubagentStatus("FAILED"), true);
});

test("applyHarnessEvent applies a committed session projection exactly once", () => {
  const exposed = {
    schema: "harness.agent-event",
    schemaVersion: 2,
    runId: "55",
    sequence: 1,
    eventId: "event-exposed-1",
    eventType: "SUBAGENT_EXPOSED",
    kind: "SUBAGENT",
    phase: "EVENT",
    data: {
      agentId: "research-agent",
      sessionId: "child-runtime-research",
      parentSessionId: "session-root",
      label: "资料收集",
    },
    projection: {
      subagent: {
        sessionId: "child-runtime-research",
        parentSessionId: "session-root",
        displayName: "资料收集",
        assignment: "资料收集",
        status: "AVAILABLE",
        displayOrder: 0,
        updatedAt: "2026-08-11T10:00:00",
      },
    },
  };

  const once = applyHarnessEvent(createHarnessUiState(), exposed);
  const duplicate = applyHarnessEvent(once, exposed);

  assert.equal(duplicate, once);
  assert.equal(orderedHarnessSubagents(once).length, 1);
  assert.equal(once.subagentsBySession["child-runtime-research"].status, "AVAILABLE");
  assert.equal(once.subagentsBySession["child-runtime-research"].assignment, "资料收集");
  assert.equal(once.subagentsBySession["child-runtime-research"].parentSessionId, "session-root");
});

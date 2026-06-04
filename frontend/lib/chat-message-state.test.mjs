import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  toRenderableTurns,
  toUiChatMessage,
} from "./chat-message-state.ts";

test("buildPendingAssistantTurn creates user, reasoning, and assistant placeholders", () => {
  const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);

  assert.equal(userMessage.role, "user");
  assert.equal(reasoningMessage.messageType, "REASONING");
  assert.equal(reasoningMessage.content, "");
  assert.equal(assistantMessage.messageType, "AI");
  assert.equal(assistantMessage.content, "");
});

test("applyReasoningChunk appends only reasoning content", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const next = applyReasoningChunk([reasoningMessage, assistantMessage], reasoningMessage.id, "先分析");

  assert.equal(next[0].content, "先分析");
  assert.equal(next[1].content, "");
});

test("applyAssistantChunk appends only assistant content", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const next = applyAssistantChunk([reasoningMessage, assistantMessage], assistantMessage.id, "最终答案");

  assert.equal(next[0].content, "");
  assert.equal(next[1].content, "最终答案");
});

test("applyBlockedState keeps reasoning content and converts assistant placeholder to blocked", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const withReasoning = applyReasoningChunk([reasoningMessage, assistantMessage], reasoningMessage.id, "先分析");
  const blocked = applyBlockedState(withReasoning, assistantMessage.id, "命中安全规则");

  assert.equal(blocked[0].messageType, "REASONING");
  assert.equal(blocked[0].content, "先分析");
  assert.equal(blocked[1].role, "blocked");
  assert.equal(blocked[1].messageType, "SYSTEM");
  assert.equal(blocked[1].content, "命中安全规则");
});

test("toRenderableTurns groups reasoning before blocked message", () => {
  const turns = toRenderableTurns([
    { id: "1", role: "assistant", messageType: "REASONING", content: "先列风险", createdAt: "" },
    { id: "2", role: "blocked", messageType: "SYSTEM", content: "命中安全规则", createdAt: "" },
  ]);

  assert.deepEqual(turns, [
    {
      kind: "blocked",
      reasoning: "先列风险",
      answer: "",
      blocked: "命中安全规则",
      id: "2",
    },
  ]);
});

test("toRenderableTurns groups reasoning before assistant reply", () => {
  const turns = toRenderableTurns([
    { id: "1", role: "assistant", messageType: "REASONING", content: "先列约束", createdAt: "" },
    { id: "2", role: "assistant", messageType: "AI", content: "最终答案", createdAt: "" },
  ]);

  assert.deepEqual(turns, [
    {
      kind: "assistant",
      reasoning: "先列约束",
      answer: "最终答案",
      blocked: null,
      id: "2",
    },
  ]);
});

test("toRenderableTurns exposes image messages as image turns", () => {
  const turns = toRenderableTurns([
    {
      id: "501",
      role: "assistant",
      messageType: "IMAGE",
      content: "一只白猫",
      resources: [
        {
          id: "resource-1",
          kind: "IMAGE",
          viewUrl: "/api/chat/resources/resource-1/content",
          downloadUrl: "/api/chat/resources/resource-1/download",
          fileName: "generated.png",
          mimeType: "image/png",
          fileSize: 3,
          width: 1024,
          height: 1024,
        },
      ],
      createdAt: "",
    },
  ]);

  assert.deepEqual(turns, [
    {
      kind: "image",
      id: "501",
      content: "一只白猫",
      resources: [
        {
          id: "resource-1",
          kind: "IMAGE",
          viewUrl: "/api/chat/resources/resource-1/content",
          downloadUrl: "/api/chat/resources/resource-1/download",
          fileName: "generated.png",
          mimeType: "image/png",
          fileSize: 3,
          width: 1024,
          height: 1024,
        },
      ],
    },
  ]);
});

test("applyImageMessage replaces an empty assistant placeholder", () => {
  const { userMessage, assistantMessage } = buildPendingAssistantTurn("/image 一只白猫", 100);
  const imageMessage = {
    id: "501",
    role: "assistant",
    messageType: "IMAGE",
    content: "一只白猫",
    resources: [],
    createdAt: "",
  };

  const next = applyImageMessage([userMessage, assistantMessage], assistantMessage.id, imageMessage);

  assert.equal(next.length, 2);
  assert.equal(next[1].id, "501");
  assert.equal(next[1].messageType, "IMAGE");
});

test("toRenderableTurns leaves legacy think-tag assistant content untouched", () => {
  const turns = toRenderableTurns([
    {
      id: "legacy-1",
      role: "assistant",
      messageType: "AI",
      content: "<think>旧思考</think>旧答案",
      createdAt: "",
    },
  ]);

  assert.equal(turns[0].kind, "assistant");
  assert.equal(turns[0].reasoning, null);
  assert.equal(turns[0].answer, "<think>旧思考</think>旧答案");
});

test("toUiChatMessage preserves reasoning message type from history payload", () => {
  const uiMessage = toUiChatMessage({
    id: "history-1",
    role: "assistant",
    messageType: "REASONING",
    content: "先看上下文",
    createdAt: "",
  });

  assert.equal(uiMessage.messageType, "REASONING");
  assert.equal(uiMessage.role, "assistant");
});

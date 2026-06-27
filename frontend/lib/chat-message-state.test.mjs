import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyAgentStep,
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  removeEmptyAssistantPlaceholders,
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
  assert.deepEqual(assistantMessage.agentSteps, []);
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

test("applyAgentStep upserts parallel steps on assistant message", () => {
  const messages = [{ id: "assistant-1", role: "assistant", messageType: "AI", content: "", agentSteps: [] }];

  const next = applyAgentStep(messages, "assistant-1", {
    invocationId: "i1",
    nodeId: "n1",
    nodeName: "客户信息提取",
    topology: "AI_AGENT",
    status: "running",
    depth: 1,
    sequence: 1,
  });

  assert.equal(next[0].agentSteps.length, 1);
  assert.equal(next[0].agentSteps[0].status, "running");
});

test("applyAgentStep updates existing step and sorts new parallel steps", () => {
  const messages = [{ id: "assistant-1", role: "assistant", messageType: "AI", content: "", agentSteps: [] }];
  const withSecondStep = applyAgentStep(messages, "assistant-1", {
    invocationId: "i2",
    nodeId: "n2",
    nodeName: "库存查询",
    topology: "AI_AGENT",
    status: "running",
    depth: 1,
    sequence: 2,
  });
  const withFirstStep = applyAgentStep(withSecondStep, "assistant-1", {
    invocationId: "i1",
    nodeId: "n1",
    nodeName: "客户信息提取",
    topology: "AI_AGENT",
    status: "running",
    depth: 1,
    sequence: 1,
  });
  const completed = applyAgentStep(withFirstStep, "assistant-1", {
    invocationId: "i1",
    nodeId: "n1",
    nodeName: "客户信息提取",
    topology: "AI_AGENT",
    status: "completed",
    depth: 1,
    sequence: 1,
  });

  assert.deepEqual(completed[0].agentSteps.map((step) => step.invocationId), ["i1", "i2"]);
  assert.equal(completed[0].agentSteps[0].status, "completed");
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
      agentSteps: [],
      resources: [],
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
      agentSteps: [],
      resources: [],
    },
  ]);
});

test("toRenderableTurns keeps assistant audio resources with assistant answer", () => {
  const turns = toRenderableTurns([
    {
      id: "audio-answer-1",
      role: "assistant",
      messageType: "AI",
      content: "这是语音回复",
      resources: [
        {
          id: "audio-resource-1",
          type: "AUDIO",
          role: "GENERATED",
          viewUrl: "/api/chat/resources/audio-resource-1/content",
          downloadUrl: "/api/chat/resources/audio-resource-1/download",
          fileName: "answer.mp3",
          mimeType: "audio/mpeg",
          fileSize: 128,
          width: null,
          height: null,
        },
      ],
      createdAt: "",
    },
  ]);

  assert.equal(turns[0].kind, "assistant");
  assert.equal(turns[0].resources.length, 1);
  assert.equal(turns[0].resources[0].mimeType, "audio/mpeg");
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
          type: "IMAGE",
          role: "GENERATED",
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
          type: "IMAGE",
          role: "GENERATED",
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

test("applyImageMessage inserts image before an empty assistant placeholder", () => {
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

  assert.equal(next.length, 3);
  assert.equal(next[1].id, "501");
  assert.equal(next[1].messageType, "IMAGE");
  assert.equal(next[2].id, assistantMessage.id);
  assert.equal(next[2].messageType, "AI");
});

test("removeEmptyAssistantPlaceholders removes unused assistant placeholders after image-only streams", () => {
  const { userMessage, assistantMessage } = buildPendingAssistantTurn("/image 一只白猫", 100);
  const imageMessage = {
    id: "501",
    role: "assistant",
    messageType: "IMAGE",
    content: "一只白猫",
    resources: [],
    createdAt: "",
  };

  const withImage = applyImageMessage([userMessage, assistantMessage], assistantMessage.id, imageMessage);
  const cleaned = removeEmptyAssistantPlaceholders(withImage);

  assert.deepEqual(cleaned.map((message) => message.id), [userMessage.id, "501"]);
});

test("applyImageMessage keeps assistant placeholder for text that follows tool image output", () => {
  const { userMessage, assistantMessage } = buildPendingAssistantTurn("生成猫的肖像画", 100);
  const imageMessage = {
    id: "501",
    role: "assistant",
    messageType: "IMAGE",
    content: "A beautiful portrait of a Ragdoll cat",
    resources: [
      {
        id: "resource-1",
        type: "IMAGE",
        role: "GENERATED",
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
  };

  const withImage = applyImageMessage([userMessage, assistantMessage], assistantMessage.id, imageMessage);
  const withAnswer = applyAssistantChunk(withImage, assistantMessage.id, "喵～这是我的自画像！");
  const turns = toRenderableTurns(withAnswer);

  assert.equal(turns[1].kind, "image");
  assert.equal(turns[2].kind, "assistant");
  assert.equal(turns[2].answer, "喵～这是我的自画像！");
});

test("toRenderableTurns groups reasoning with final assistant reply when images are emitted between them", () => {
  const turns = toRenderableTurns([
    {
      id: "reasoning-1",
      role: "assistant",
      messageType: "REASONING",
      content: "先生成图片，再回复用户",
      createdAt: "",
    },
    {
      id: "image-1",
      role: "assistant",
      messageType: "IMAGE",
      content: "A beautiful portrait of a Ragdoll cat",
      resources: [
        {
          id: "resource-1",
          type: "IMAGE",
          role: "GENERATED",
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
    {
      id: "assistant-1",
      role: "assistant",
      messageType: "AI",
      content: "喵～这是我的自画像！",
      createdAt: "",
    },
  ]);

  assert.equal(turns.length, 2);
  assert.equal(turns[0].kind, "image");
  assert.equal(turns[1].kind, "assistant");
  assert.equal(turns[1].reasoning, "先生成图片，再回复用户");
  assert.equal(turns[1].answer, "喵～这是我的自画像！");
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

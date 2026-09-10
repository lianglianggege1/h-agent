import assert from "node:assert/strict";
import { test } from "node:test";
import {
  applyAgentStep,
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyReasoningChunk,
  applyPersistedMessage,
  attachAutomationProposals,
  buildPendingAssistantTurn,
  hasPendingVideoGeneration,
  removeEmptyAssistantPlaceholders,
  toRenderableTurns,
  toUiChatMessage,
  toUiChatMessages,
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

test("hasPendingVideoGeneration waits for a video resource instead of matching status text", () => {
  const pending = [{
    id: "video-message",
    role: "assistant",
    messageType: "VIDEO",
    content: "视频生成中，请继续聊天。",
    resources: [],
  }];
  const completed = [{
    ...pending[0],
    content: "视频已生成。",
    resources: [{
      id: "video-resource",
      type: "VIDEO",
      role: "GENERATED",
      viewUrl: "/api/chat/resources/video-resource/content",
      downloadUrl: "/api/chat/resources/video-resource/download",
      fileName: "video.mp4",
      mimeType: "video/mp4",
      fileSize: 1,
      width: null,
      height: null,
    }],
  }];

  assert.equal(hasPendingVideoGeneration(pending), true);
  assert.equal(hasPendingVideoGeneration(completed), false);
  assert.equal(hasPendingVideoGeneration([...completed, ...pending]), true);
});

test("hasPendingVideoGeneration stops polling after provider rejection", () => {
  const failed = [{
    id: "failed-video-message",
    role: "assistant",
    messageType: "VIDEO",
    content: "视频生成失败：MiniMax error 1026: input new_sensitive, input first_frame_image sensitive",
    resources: [],
  }];

  assert.equal(hasPendingVideoGeneration(failed), false);
});

test("toUiChatMessages surfaces a completed asynchronous video message after refresh", () => {
  const localMessages = [{
    id: "assistant-message",
    role: "assistant",
    messageType: "AI",
    content: "视频任务已提交。",
    resources: [],
  }];
  assert.equal(hasPendingVideoGeneration(localMessages), false);

  const refreshed = toUiChatMessages([
    ...localMessages,
    {
      id: "video-message",
      role: "assistant",
      messageType: "VIDEO",
      content: "视频已生成。",
      resources: [{
        id: "video-resource",
        type: "VIDEO",
        role: "GENERATED",
        viewUrl: "/api/chat/resources/video-resource/content",
        downloadUrl: "/api/chat/resources/video-resource/download",
        fileName: "video.mp4",
        mimeType: "video/mp4",
        fileSize: 3,
        width: null,
        height: null,
      }],
      createdAt: "2026-08-07T09:00:00Z",
    },
  ]);

  assert.equal(refreshed[1].messageType, "VIDEO");
  assert.equal(refreshed[1].resources[0].mimeType, "video/mp4");
  assert.equal(hasPendingVideoGeneration(refreshed), false);
  assert.equal(toRenderableTurns(refreshed)[1].resources[0].type, "VIDEO");
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

test("applyPersistedMessage replaces local user placeholder and keeps audio resources", () => {
  const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const persistedUser = {
    id: "101",
    role: "user",
    messageType: "USER",
    content: "你好",
    resources: [
      {
        id: "audio-1",
        type: "AUDIO",
        role: "ATTACHMENT",
        viewUrl: "/api/chat/resources/audio-1/content",
        downloadUrl: "/api/chat/resources/audio-1/download",
        fileName: "call.webm",
        mimeType: "audio/webm",
        fileSize: 3,
        width: null,
        height: null,
      },
    ],
    createdAt: "2026-06-27T10:00:00",
  };

  const next = applyPersistedMessage([userMessage, reasoningMessage, assistantMessage], userMessage.id, persistedUser);

  assert.equal(next[0].id, "101");
  assert.equal(next[0].resources.length, 1);
  assert.equal(next[1].id, reasoningMessage.id);
  assert.equal(next[2].id, assistantMessage.id);
});

test("applyPersistedMessage replaces assistant placeholder without losing streamed agent steps", () => {
  const { reasoningMessage, assistantMessage } = buildPendingAssistantTurn("你好", 100);
  const withStep = applyAgentStep([reasoningMessage, assistantMessage], assistantMessage.id, {
    invocationId: "i1",
    nodeId: "n1",
    nodeName: "查询",
    topology: "AI_AGENT",
    status: "completed",
    depth: 1,
    sequence: 1,
  });
  const persistedAssistant = {
    id: "202",
    role: "assistant",
    messageType: "AI",
    content: "最终答案",
    resources: [
      {
        id: "audio-2",
        type: "AUDIO",
        role: "GENERATED",
        viewUrl: "/api/chat/resources/audio-2/content",
        downloadUrl: "/api/chat/resources/audio-2/download",
        fileName: "answer.mp3",
        mimeType: "audio/mpeg",
        fileSize: 9,
        width: null,
        height: null,
      },
    ],
    createdAt: "2026-06-27T10:01:00",
  };

  const next = applyPersistedMessage(withStep, assistantMessage.id, persistedAssistant);

  assert.equal(next[1].id, "202");
  assert.equal(next[1].content, "最终答案");
  assert.equal(next[1].resources.length, 1);
  assert.equal(next[1].agentSteps.length, 1);
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

test("toRenderableTurns keeps a persisted system assignment as its own turn", () => {
  const turns = toRenderableTurns([
    { id: "assignment-1", role: "system", messageType: "SYSTEM", content: "收集官方资料并标注来源", createdAt: "" },
    { id: "answer-1", role: "assistant", messageType: "AI", content: "已完成。", createdAt: "" },
  ]);

  assert.deepEqual(turns[0], {
    kind: "system",
    id: "assignment-1",
    content: "收集官方资料并标注来源",
  });
  assert.equal(turns[1].kind, "assistant");
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

// ---- attachAutomationProposals tests ----

function makeProposal(overrides = {}) {
  return {
    id: "proposal-1",
    action: "CREATE",
    taskId: null,
    status: "PENDING",
    createdAt: "2026-09-10T00:00:00Z",
    expiresAt: "2026-09-11T00:00:00Z",
    resultTaskId: null,
    sourceSessionId: "session-1",
    name: "晨报",
    instruction: "汇总今天的行业动态",
    agentId: "standard-chat",
    cronExpression: "0 0 9 * * *",
    zoneId: "Asia/Shanghai",
    deliverySink: "SESSION",
    upcomingFires: [],
    sourceAgentRunId: 1,
    anchorMessageId: "assistant-1",
    anchorPlacement: "AFTER",
    ...overrides,
  };
}

test("attachAutomationProposals inserts proposal after its anchor message", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
    { kind: "user", id: "user-2", content: "谢谢" },
    { kind: "assistant", id: "assistant-2", reasoning: null, answer: "不客气", blocked: null, agentSteps: [], resources: [] },
  ];
  const proposals = [makeProposal({ anchorMessageId: "assistant-1" })];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 5);
  assert.equal(timeline[0].kind, "turn");
  assert.equal(timeline[0].turn.id, "user-1");
  assert.equal(timeline[1].kind, "turn");
  assert.equal(timeline[1].turn.id, "assistant-1");
  assert.equal(timeline[2].kind, "automation-proposal");
  assert.equal(timeline[2].proposal.id, "proposal-1");
  assert.equal(timeline[3].kind, "turn");
  assert.equal(timeline[3].turn.id, "user-2");
});

test("attachAutomationProposals keeps position after appending more messages", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
  ];
  const proposals = [makeProposal({ anchorMessageId: "assistant-1" })];

  const timeline1 = attachAutomationProposals(turns, proposals);
  assert.equal(timeline1.length, 3);
  assert.equal(timeline1[2].kind, "automation-proposal");

  const extendedTurns = [
    ...turns,
    { kind: "user", id: "user-2", content: "继续聊天" },
    { kind: "assistant", id: "assistant-2", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
    { kind: "user", id: "user-3", content: "更多消息" },
    { kind: "assistant", id: "assistant-3", reasoning: null, answer: "收到", blocked: null, agentSteps: [], resources: [] },
  ];

  const timeline2 = attachAutomationProposals(extendedTurns, proposals);
  assert.equal(timeline2.length, 7);
  assert.equal(timeline2[2].kind, "automation-proposal");
  assert.equal(timeline2[2].proposal.id, "proposal-1");
});

test("attachAutomationProposals keeps position after status change", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
  ];

  const pendingTimeline = attachAutomationProposals(turns, [makeProposal({ status: "PENDING" })]);
  const confirmedTimeline = attachAutomationProposals(turns, [makeProposal({ status: "CONFIRMED" })]);

  assert.equal(pendingTimeline.length, 3);
  assert.equal(confirmedTimeline.length, 3);
  assert.equal(pendingTimeline[2].kind, "automation-proposal");
  assert.equal(confirmedTimeline[2].kind, "automation-proposal");
  assert.equal(confirmedTimeline[2].proposal.status, "CONFIRMED");
});

test("attachAutomationProposals does not output proposals with null anchor", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
  ];
  const proposals = [makeProposal({ anchorMessageId: null })];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 2);
  assert.equal(timeline.every((item) => item.kind === "turn"), true);
});

test("attachAutomationProposals does not output proposals when anchor is not loaded", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
  ];
  const proposals = [makeProposal({ anchorMessageId: "assistant-999" })];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 2);
  assert.equal(timeline.every((item) => item.kind === "turn"), true);
});

test("attachAutomationProposals sorts multiple proposals on same anchor by createdAt and id", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
    { kind: "assistant", id: "assistant-1", reasoning: null, answer: "好的", blocked: null, agentSteps: [], resources: [] },
  ];
  const proposals = [
    makeProposal({ id: "proposal-2", createdAt: "2026-09-10T00:00:01Z" }),
    makeProposal({ id: "proposal-1", createdAt: "2026-09-10T00:00:01Z" }),
    makeProposal({ id: "proposal-3", createdAt: "2026-09-10T00:00:00Z" }),
  ];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 5);
  assert.equal(timeline[2].kind, "automation-proposal");
  assert.equal(timeline[2].proposal.id, "proposal-3");
  assert.equal(timeline[3].proposal.id, "proposal-1");
  assert.equal(timeline[4].proposal.id, "proposal-2");
});

test("attachAutomationProposals anchors to user message when assistant message is missing", () => {
  const turns = [
    { kind: "user", id: "user-1", content: "帮我创建晨报" },
  ];
  const proposals = [makeProposal({ anchorMessageId: "user-1" })];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 2);
  assert.equal(timeline[1].kind, "automation-proposal");
});

test("attachAutomationProposals works with reasoning merged into assistant turn", () => {
  const turns = toRenderableTurns([
    { id: "reasoning-1", role: "assistant", messageType: "REASONING", content: "先分析", createdAt: "" },
    { id: "assistant-1", role: "assistant", messageType: "AI", content: "最终答案", createdAt: "" },
  ]);
  const proposals = [makeProposal({ anchorMessageId: "assistant-1" })];

  const timeline = attachAutomationProposals(turns, proposals);

  assert.equal(timeline.length, 2);
  assert.equal(timeline[0].kind, "turn");
  assert.equal(timeline[0].turn.kind, "assistant");
  assert.equal(timeline[0].turn.id, "assistant-1");
  assert.equal(timeline[1].kind, "automation-proposal");
});

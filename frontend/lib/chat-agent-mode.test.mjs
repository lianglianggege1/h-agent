import assert from "node:assert/strict";
import { test } from "node:test";
import {
  agentChatHref,
  agentModeFromSession,
  buildNewSessionPayload,
  buildChatSendPayload,
  chatSessionHref,
  domainAgentsFromCatalog,
  filterDomainAgents,
  nextSelectedPromptIdForHydratedSession,
  shouldCreateSessionForRequestedAgent,
  isStandardAgent,
} from "./chat-agent-mode.ts";

test("agentModeFromSession identifies standard chat", () => {
  assert.equal(isStandardAgent("standard-chat"), true);
  assert.equal(agentModeFromSession({ agentId: "standard-chat" }), "standard");
});

test("agentModeFromSession identifies domain agent", () => {
  assert.equal(isStandardAgent("car-rental-assistant"), false);
  assert.equal(agentModeFromSession({ agentId: "car-rental-assistant" }), "domain");
});

test("agentModeFromSession uses runtimeType to identify harness sessions", () => {
  assert.equal(
    agentModeFromSession({
      agentId: "harness-agent",
      runtimeType: "HARNESS_STREAMING",
    }),
    "harness",
  );
  assert.equal(
    agentModeFromSession({
      agentId: "misleading-id",
      runtimeType: "STANDARD_STREAMING_CHAT",
    }),
    "standard",
  );
});

const catalog = [
  {
    agentId: "standard-chat",
    displayName: "通用助手",
    domain: "通用",
    summary: "自由聊天",
    tags: ["聊天"],
    runtimeType: "STANDARD_STREAMING_CHAT",
  },
  {
    agentId: "harness-agent",
    displayName: "协作 Agent",
    domain: "协作",
    summary: "复杂任务拆分",
    tags: ["并行"],
    runtimeType: "HARNESS_STREAMING",
  },
  {
    agentId: "car-rental-assistant",
    displayName: "租车助手",
    domain: "出行",
    summary: "处理租车和道路救援",
    tags: ["救援"],
    runtimeType: "AGENTIC_SYNC",
  },
  {
    agentId: "legal-assistant",
    displayName: "合同助手",
    domain: "法务",
    summary: "审阅合同",
    tags: ["合同"],
    runtimeType: "AGENTIC_SYNC",
  },
];

test("domainAgentsFromCatalog excludes standard and harness agents", () => {
  assert.deepEqual(
    domainAgentsFromCatalog(catalog).map((agent) => agent.agentId),
    ["car-rental-assistant", "legal-assistant"],
  );
});

test("filterDomainAgents searches name, domain, summary and tags with domain filtering", () => {
  assert.deepEqual(filterDomainAgents(catalog, "救援", "全部").map((agent) => agent.agentId), [
    "car-rental-assistant",
  ]);
  assert.deepEqual(filterDomainAgents(catalog, "法务", "全部").map((agent) => agent.agentId), [
    "legal-assistant",
  ]);
  assert.deepEqual(filterDomainAgents(catalog, "", "出行").map((agent) => agent.agentId), [
    "car-rental-assistant",
  ]);
});

test("buildChatSendPayload sends domain agent id and null prompt", () => {
  assert.deepEqual(
    buildChatSendPayload({
      message: "救援",
      sessionId: "s1",
      agentId: "car-rental-assistant",
      promptId: 9,
    }),
    {
      message: "救援",
      sessionId: "s1",
      agentId: "car-rental-assistant",
      promptId: null,
      resources: null,
    },
  );
});

test("buildChatSendPayload keeps standard agent id and prompt", () => {
  assert.deepEqual(
    buildChatSendPayload({
      message: "聊天",
      sessionId: "s2",
      agentId: "standard-chat",
      promptId: 9,
    }),
    {
      message: "聊天",
      sessionId: "s2",
      agentId: "standard-chat",
      promptId: 9,
      resources: null,
    },
  );
});

test("agentChatHref points agent quick start to unified chat", () => {
  assert.equal(agentChatHref("car/rental"), "/chat?agentId=car%2Frental");
});

test("chatSessionHref canonicalizes a selected session route", () => {
  assert.equal(chatSessionHref("session/1"), "/chat?sessionId=session%2F1");
});

test("buildNewSessionPayload uses the selected target agent instead of the current agent", () => {
  assert.deepEqual(
    buildNewSessionPayload({
      currentSessionId: "domain-session",
      targetAgentId: "standard-chat",
      promptId: 12,
    }),
    {
      currentSessionId: "domain-session",
      agentId: "standard-chat",
      promptId: 12,
    },
  );
  assert.deepEqual(
    buildNewSessionPayload({
      currentSessionId: "standard-session",
      targetAgentId: "car-rental-assistant",
      promptId: 12,
    }),
    {
      currentSessionId: "standard-session",
      agentId: "car-rental-assistant",
      promptId: null,
    },
  );
  assert.deepEqual(
    buildNewSessionPayload({
      currentSessionId: "domain-session",
      targetAgentId: "harness-agent",
      promptId: 12,
    }),
    {
      currentSessionId: "domain-session",
      agentId: "harness-agent",
      promptId: null,
    },
  );
});

test("nextSelectedPromptIdForHydratedSession keeps standard prompt while viewing domain agents", () => {
  assert.equal(
    nextSelectedPromptIdForHydratedSession({
      hydratedAgentId: "car-rental-assistant",
      hydratedPromptId: null,
      currentPromptId: 12,
      fallbackPromptId: 3,
    }),
    12,
  );
  assert.equal(
    nextSelectedPromptIdForHydratedSession({
      hydratedAgentId: "car-rental-assistant",
      hydratedPromptId: null,
      currentPromptId: null,
      fallbackPromptId: 3,
    }),
    3,
  );
  assert.equal(
    nextSelectedPromptIdForHydratedSession({
      hydratedAgentId: "standard-chat",
      hydratedPromptId: 9,
      currentPromptId: 12,
      fallbackPromptId: 3,
    }),
    9,
  );
});

test("shouldCreateSessionForRequestedAgent only switches when requested agent differs", () => {
  assert.equal(
    shouldCreateSessionForRequestedAgent({
      requestedAgentId: "car-rental-assistant",
      currentAgentId: "standard-chat",
      sessionId: "s1",
    }),
    true,
  );
  assert.equal(
    shouldCreateSessionForRequestedAgent({
      requestedAgentId: "car-rental-assistant",
      currentAgentId: "car-rental-assistant",
      sessionId: "s1",
    }),
    false,
  );
  assert.equal(
    shouldCreateSessionForRequestedAgent({
      requestedAgentId: null,
      currentAgentId: "standard-chat",
      sessionId: "s1",
    }),
    false,
  );
});

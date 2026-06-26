import assert from "node:assert/strict";
import { test } from "node:test";
import {
  agentChatHref,
  agentModeFromSession,
  buildNewSessionPayload,
  buildChatSendPayload,
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

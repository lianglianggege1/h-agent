export const STANDARD_AGENT_ID = "standard-chat";

export function isStandardAgent(agentId: string | null | undefined) {
  return !agentId || agentId === STANDARD_AGENT_ID;
}

export function agentModeFromSession(session: { agentId: string | null | undefined }) {
  return isStandardAgent(session.agentId) ? "standard" : "domain";
}

export function buildChatSendPayload(input: {
  message: string;
  sessionId: string;
  agentId: string;
  promptId: number | null;
}) {
  const standard = isStandardAgent(input.agentId);
  return {
    message: input.message,
    sessionId: input.sessionId,
    promptId: standard ? input.promptId : null,
    agentId: standard ? STANDARD_AGENT_ID : input.agentId,
  };
}

export function agentChatHref(agentId: string) {
  return `/chat?agentId=${encodeURIComponent(agentId)}`;
}

export function shouldCreateSessionForRequestedAgent(input: {
  requestedAgentId: string | null | undefined;
  currentAgentId: string | null | undefined;
  sessionId: string | null | undefined;
}) {
  return Boolean(input.sessionId && input.requestedAgentId && input.requestedAgentId !== input.currentAgentId);
}

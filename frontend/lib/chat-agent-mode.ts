export const STANDARD_AGENT_ID = "standard-chat";
export const HARNESS_AGENT_ID = "harness-agent";
export const STANDARD_RUNTIME_TYPE = "STANDARD_STREAMING_CHAT";
export const DOMAIN_RUNTIME_TYPE = "AGENTIC_SYNC";
export const HARNESS_RUNTIME_TYPE = "HARNESS_STREAMING";

export type ChatAgentMode = "standard" | "domain" | "harness";

export function isStandardAgent(agentId: string | null | undefined) {
  return !agentId || agentId === STANDARD_AGENT_ID;
}

export function agentModeFromSession(session: {
  runtimeType?: string | null;
  agentId?: string | null;
}): ChatAgentMode {
  if (session.runtimeType === HARNESS_RUNTIME_TYPE) return "harness";
  if (session.runtimeType === DOMAIN_RUNTIME_TYPE) return "domain";
  if (session.runtimeType === STANDARD_RUNTIME_TYPE) return "standard";

  // Compatibility for sessions created before runtimeType was returned by the API.
  if (session.agentId === HARNESS_AGENT_ID) return "harness";
  return isStandardAgent(session.agentId) ? "standard" : "domain";
}

type SelectableAgent = {
  agentId: string;
  displayName: string;
  domain: string;
  summary: string;
  tags: string[];
  runtimeType: string;
};

export function domainAgentsFromCatalog<T extends SelectableAgent>(agents: T[]): T[] {
  return agents.filter((agent) => agent.runtimeType === DOMAIN_RUNTIME_TYPE);
}

export function filterDomainAgents<T extends SelectableAgent>(
  agents: T[],
  search: string,
  selectedDomain: string,
): T[] {
  const keyword = search.trim().toLowerCase();
  return domainAgentsFromCatalog(agents).filter((agent) => {
    const domainMatched = selectedDomain === "全部" || agent.domain === selectedDomain;
    const keywordMatched =
      !keyword ||
      agent.displayName.toLowerCase().includes(keyword) ||
      agent.agentId.toLowerCase().includes(keyword) ||
      agent.domain.toLowerCase().includes(keyword) ||
      agent.summary.toLowerCase().includes(keyword) ||
      agent.tags.some((tag) => tag.toLowerCase().includes(keyword));
    return domainMatched && keywordMatched;
  });
}

export function buildChatSendPayload(input: {
  message: string;
  sessionId: string;
  agentId: string;
  promptId: number | null;
  resources?: Array<{ resourceId: string; role: string; source: string }>;
}) {
  const standard = isStandardAgent(input.agentId);
  return {
    message: input.message,
    sessionId: input.sessionId,
    promptId: standard ? input.promptId : null,
    agentId: standard ? STANDARD_AGENT_ID : input.agentId,
    resources: input.resources ?? null,
  };
}

export function buildNewSessionPayload(input: {
  currentSessionId: string | null | undefined;
  targetAgentId: string | null | undefined;
  promptId: number | null;
}) {
  const targetAgentId = input.targetAgentId || STANDARD_AGENT_ID;
  const standard = isStandardAgent(targetAgentId);
  return {
    currentSessionId: input.currentSessionId ?? null,
    promptId: standard ? input.promptId : null,
    agentId: standard ? STANDARD_AGENT_ID : targetAgentId,
  };
}

export function nextSelectedPromptIdForHydratedSession(input: {
  hydratedAgentId: string | null | undefined;
  hydratedPromptId: number | null;
  currentPromptId: number | null;
  fallbackPromptId: number | null;
}) {
  if (isStandardAgent(input.hydratedAgentId)) {
    return input.hydratedPromptId ?? input.fallbackPromptId;
  }
  return input.currentPromptId ?? input.fallbackPromptId;
}

export function agentChatHref(agentId: string) {
  return `/chat?agentId=${encodeURIComponent(agentId)}`;
}

export function chatSessionHref(sessionId: string) {
  return `/chat?sessionId=${encodeURIComponent(sessionId)}`;
}

export function shouldCreateSessionForRequestedAgent(input: {
  requestedAgentId: string | null | undefined;
  currentAgentId: string | null | undefined;
  sessionId: string | null | undefined;
}) {
  return Boolean(input.sessionId && input.requestedAgentId && input.requestedAgentId !== input.currentAgentId);
}

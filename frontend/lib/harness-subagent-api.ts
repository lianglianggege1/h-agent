import { apiFetch } from "./http.ts";
import type { HarnessSubagentSummary } from "./harness-subagent-types";

type MessageResourceUse = {
  resourceId: string;
  role: string;
  source: string;
};

export function getHarnessSubagents(parentSessionId: string) {
  return apiFetch<HarnessSubagentSummary[]>(
    `/api/chat/sessions/${encodeURIComponent(parentSessionId)}/subagents`,
  );
}

export function buildSubagentMessageRequest(input: {
  message: string;
  sessionId: string;
  agentId: string;
  resources?: MessageResourceUse[] | null;
}) {
  return {
    message: input.message,
    sessionId: input.sessionId,
    promptId: null,
    agentId: input.agentId,
    resources: input.resources ?? null,
  };
}

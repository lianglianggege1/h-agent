import type { ChatSessionMessage } from "./chat-sessions";

export type HarnessSubagentStatus =
  | "AVAILABLE"
  | "RUNNING"
  | "COMPLETED"
  | "FAILED";

export type HarnessSubagentSummary = {
  sessionId: string;
  parentSessionId: string;
  displayName: string;
  assignment: string;
  status: HarnessSubagentStatus;
  displayOrder: number;
  updatedAt: string;
};

export type HarnessProjectionPatch = {
  subagent: HarnessSubagentSummary | null;
  committedMessage?: ChatSessionMessage | null;
};

export type HarnessAgentEvent = {
  schema: string;
  schemaVersion: number;
  runId: string;
  sequence: number;
  eventId: string;
  eventType: string;
  kind: string;
  phase: string;
  source?: {
    scope: "PARENT" | "SUBAGENT" | string;
    path: string | null;
  } | null;
  correlation?: Record<string, unknown>;
  data: Record<string, unknown>;
  projection?: HarnessProjectionPatch | null;
};

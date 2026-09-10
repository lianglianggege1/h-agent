import { apiFetch } from "./http";

export type AutomationRuntime = "LANGCHAIN4J" | "AGENTSCOPE";

export type AutomationTask = {
  id: string;
  name: string;
  instruction: string;
  agentId: string;
  runtime: AutomationRuntime;
  cronExpression: string;
  zoneId: string;
  enabled: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastStatus: string | null;
  createdVia: string;
  revision: number;
  deliverySink: "SESSION" | "NONE";
  deliverySessionId: string | null;
  sessionId: string | null;
  schedulerMode: "XXL_JOB";
  schedulerSyncStatus: "NOT_SCHEDULED" | "PENDING" | "SYNCED" | "SYNC_FAILED";
  schedulerSyncedRevision: number | null;
  schedulerSyncError: string | null;
  createdAt: string;
  updatedAt: string;
};

export type AutomationRun = {
  id: string;
  taskId: string;
  taskRevision: number;
  triggerType: "MANUAL" | "SCHEDULED";
  triggerId: string | null;
  status: "QUEUED" | "RUNNING" | "CANCEL_REQUESTED" | "SUCCEEDED" | "FAILED" | "TIMED_OUT" | "CANCELLED" | "REJECTED_POLICY";
  scheduledFor: string | null;
  startedAt: string;
  finishedAt: string | null;
  sessionId: string | null;
  output: string | null;
  errorMessage: string | null;
};

export type AutomationTaskInput = {
  name: string;
  instruction: string;
  agentId: string;
  runtime: AutomationRuntime;
  cronExpression: string;
  zoneId: string;
  enabled: boolean;
  expectedRevision?: number;
  deliverySink?: "SESSION" | "NONE";
  deliverySessionId?: string | null;
  sessionId: string;
};

export type AutomationProposal = {
  id: string;
  action: "CREATE" | "UPDATE" | "ENABLE" | "DISABLE" | "DELETE";
  taskId: string | null;
  status: "PENDING" | "CONFIRMED" | "DISCARDED" | "EXPIRED";
  createdAt: string;
  expiresAt: string;
  resultTaskId: string | null;
  sourceSessionId: string | null;
  name: string | null;
  instruction: string | null;
  agentId: string | null;
  cronExpression: string | null;
  zoneId: string | null;
  deliverySink: "SESSION" | "NONE" | null;
  upcomingFires: string[];
};

export function listAutomations() {
  return apiFetch<AutomationTask[]>("/api/automations");
}

export function listAutomationProposals(sessionId?: string) {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
  return apiFetch<AutomationProposal[]>(`/api/automations/proposals${query}`);
}

export function confirmAutomationProposal(proposalId: string) {
  return apiFetch<AutomationProposal>(
    `/api/automations/proposals/${encodeURIComponent(proposalId)}/confirm`,
    { method: "POST" },
  );
}

export function discardAutomationProposal(proposalId: string) {
  return apiFetch<AutomationProposal>(
    `/api/automations/proposals/${encodeURIComponent(proposalId)}/discard`,
    { method: "POST" },
  );
}

export function createAutomation(input: AutomationTaskInput) {
  return apiFetch<AutomationTask>("/api/automations", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateAutomation(taskId: string, input: AutomationTaskInput) {
  return apiFetch<AutomationTask>(`/api/automations/${encodeURIComponent(taskId)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function enableAutomation(taskId: string, expectedRevision: number) {
  return apiFetch<AutomationTask>(`/api/automations/${encodeURIComponent(taskId)}/enable`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision }),
  });
}

export function disableAutomation(taskId: string, expectedRevision: number) {
  return apiFetch<AutomationTask>(`/api/automations/${encodeURIComponent(taskId)}/disable`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision }),
  });
}

export function deleteAutomation(taskId: string) {
  return apiFetch<null>(`/api/automations/${encodeURIComponent(taskId)}`, { method: "DELETE" });
}

export function runAutomation(taskId: string) {
  return apiFetch<AutomationRun>(`/api/automations/${encodeURIComponent(taskId)}/runs`, { method: "POST" });
}

export function listAutomationRuns(taskId: string, limit = 20) {
  return apiFetch<AutomationRun[]>(
    `/api/automations/${encodeURIComponent(taskId)}/runs?limit=${limit}`,
  );
}

export function runtimeForAgent(runtimeType: string): AutomationRuntime {
  return runtimeType === "HARNESS_STREAMING" ? "AGENTSCOPE" : "LANGCHAIN4J";
}

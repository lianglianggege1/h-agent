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
  createdAt: string;
  updatedAt: string;
};

export type AutomationRun = {
  id: string;
  taskId: string;
  triggerType: "MANUAL" | "SCHEDULED";
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
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
};

export function listAutomations() {
  return apiFetch<AutomationTask[]>("/api/automations");
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

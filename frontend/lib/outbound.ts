import { apiFetch } from "./http.ts";

export type OutboundContact = {
  id: number;
  phone: string;
  name: string | null;
  consentBasis: string;
  dnc: boolean;
  createdAt: number;
};

export type PhoneAgent = { id: string; name: string; phoneCapable: boolean };

export type OutboundCall = {
  id: number;
  phone: string;
  name: string;
  stage: "QUEUED" | "PREPARING" | "DIALING" | "ACTIVE" | "ENDING" | "UNKNOWN" | "FINISHED" | "CANCELLED";
  connectResult: string;
  dialogueResult: string;
  reason: string;
  voiceCallId: string;
  sessionId: string;
};

export type OutboundTaskSummary = {
  id: number;
  name: string;
  status: "READY" | "RUNNING" | "PAUSED" | "STOPPED" | "COMPLETED";
  statusReason: string;
  total: number;
  stageCounts: Record<string, number>;
};

export type OutboundTaskDetail = OutboundTaskSummary & {
  communicationGoal: string;
  requestId: string;
  calls: OutboundCall[];
};

export type ContactImportRow = { phone: string; name: string; consentBasis: string };
export type TaskDraft = {
  name: string;
  agentId: string;
  communicationGoal: string;
  contactIds: number[];
};
export type DraftIdentity = { signature: string; requestId: string };

export function parseContactRows(source: string): ContactImportRow[] {
  return source
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const separator = line.includes("\t") ? "\t" : ",";
      const [phone = "", name = "", ...consent] = line.split(separator).map((part) => part.trim());
      return { phone, name, consentBasis: consent.join(separator).trim() };
    });
}

export function identityForDraft(
  current: DraftIdentity | null,
  draft: TaskDraft,
  createId: () => string = () => crypto.randomUUID(),
): DraftIdentity {
  const signature = JSON.stringify({ ...draft, contactIds: [...draft.contactIds].sort((a, b) => a - b) });
  return current?.signature === signature ? current : { signature, requestId: createId() };
}

export function importContacts(rows: ContactImportRow[]) {
  return apiFetch<{ inserted: number }>("/api/outbound/contacts/import", {
    method: "POST",
    body: JSON.stringify(rows),
  });
}

export function listContacts(page = 1, size = 100) {
  return apiFetch<{ items: OutboundContact[]; total: number; page: number; size: number }>(
    `/api/outbound/contacts?page=${page}&size=${size}`,
  );
}

export function markContactDnc(id: number) {
  return apiFetch<{ status: string }>(`/api/outbound/contacts/${id}/dnc`, { method: "PUT" });
}

export function listPhoneAgents() {
  return apiFetch<{ items: PhoneAgent[] }>("/api/outbound/agents");
}

export function createOutboundTask(draft: TaskDraft, requestId: string) {
  return apiFetch<OutboundTaskDetail>("/api/outbound/tasks", {
    method: "POST",
    body: JSON.stringify({ ...draft, requestId }),
  });
}

export function listOutboundTasks() {
  return apiFetch<{ items: OutboundTaskSummary[] }>("/api/outbound/tasks");
}

export function getOutboundTask(id: number) {
  return apiFetch<OutboundTaskDetail>(`/api/outbound/tasks/${id}`);
}

export function startOutboundTask(id: number) {
  return apiFetch<{ status: string }>(`/api/outbound/tasks/${id}/start`, { method: "POST" });
}

export function stopOutboundTask(id: number) {
  return apiFetch<{ status: string }>(`/api/outbound/tasks/${id}/stop`, { method: "POST" });
}

export function reconcileOutboundCall(id: number) {
  return apiFetch<{ id?: number; stage: string; reconciled: boolean }>(
    `/api/outbound/calls/${id}/reconcile`,
    { method: "POST" },
  );
}

export function resolveUnknownCall(id: number, resolution: string, note: string) {
  return apiFetch<{ id: number; stage: string; resolved: boolean }>(
    `/api/outbound/calls/${id}/resolve-unknown`,
    { method: "POST", body: JSON.stringify({ resolution, note }) },
  );
}

import { apiFetch } from "./http";

export type SkillSummary = {
  id: number;
  skillKey: string;
  displayName: string;
  description: string | null;
  sourceType: string;
  enabled: boolean;
  archived: boolean;
  revision: number;
  activeReleaseId: number | null;
  activeVersion: number | null;
  hasOpenProposal: boolean;
  openProposalValidationStatus: string | null;
  lastPublishedAt: string | null;
  updatedAt: string;
};

export type ProposalFile = {
  path: string;
  size: number;
  contentBase64: string;
};

export type Proposal = {
  proposalId: number | null;
  headCommitSha: string;
  revision: number;
  validationStatus: string;
  validationErrors: string[];
  validationWarnings: string[];
  files: ProposalFile[];
  updatedAt: string;
};

export type ReleaseSummary = {
  id: number;
  versionNumber: number;
  digest: string;
  size: number;
  releaseNote: string;
  status: string;
  commitSha: string;
  createdAt: string;
  revoked: boolean;
  revokeReason: string | null;
};

export type ManifestEntry = {
  path: string;
  size: number;
  sha256: string;
};

export type ReleaseDetail = {
  summary: ReleaseSummary;
  builderVersion: string;
  validationPolicyVersion: string;
  securityPolicyVersion: string;
  files: ProposalFile[];
  manifest: ManifestEntry[];
  validationWarnings: string[];
  isActive: boolean;
};

export type FileDiff = {
  path: string;
  change: string;
};

export type ReleaseCompare = {
  fromReleaseId: number;
  fromVersion: number;
  toReleaseId: number;
  toVersion: number;
  changes: FileDiff[];
  filesAdded: number;
  filesModified: number;
  filesRemoved: number;
};

export type ValidationOutcome = {
  valid: boolean;
  errors: string[];
  warnings: string[];
  headCommitSha: string;
};

export type CreateSkillPayload = {
  skillKey: string;
  displayName: string;
  description: string;
  skillMd: string;
};

export type ProposalChange = {
  path: string;
  contentBase64: string | null;
};

export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function encodeFileContent(content: string): string {
  const bytes = new TextEncoder().encode(content);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

export function decodeFileContent(contentBase64: string): string {
  const binary = atob(contentBase64);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function listSkills() {
  return apiFetch<SkillSummary[]>("/api/me/skills");
}

export function getSkill(skillId: number) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}`);
}

export function createSkill(payload: CreateSkillPayload) {
  return apiFetch<SkillSummary>("/api/me/skills", {
    method: "POST",
    headers: { "Idempotency-Key": newIdempotencyKey() },
    body: JSON.stringify(payload),
  });
}

export function deleteSkill(skillId: number) {
  return apiFetch<null>(`/api/me/skills/${skillId}`, { method: "DELETE" });
}

export function getProposal(skillId: number) {
  return apiFetch<Proposal>(`/api/me/skills/${skillId}/proposal`);
}

export function createProposal(skillId: number, baseReleaseId: number | null) {
  return apiFetch<Proposal>(`/api/me/skills/${skillId}/proposal`, {
    method: "POST",
    headers: { "Idempotency-Key": newIdempotencyKey() },
    body: JSON.stringify({ baseReleaseId }),
  });
}

export function saveProposal(skillId: number, expectedHead: string, changes: ProposalChange[]) {
  return apiFetch<Proposal>(`/api/me/skills/${skillId}/proposal`, {
    method: "PUT",
    body: JSON.stringify({ expectedHead, changes }),
  });
}

export function validateProposal(skillId: number, expectedHead: string) {
  return apiFetch<ValidationOutcome>(`/api/me/skills/${skillId}/proposal/validate`, {
    method: "POST",
    body: JSON.stringify({ expectedHead }),
  });
}

export function discardProposal(skillId: number, expectedHead: string) {
  const search = new URLSearchParams({ expectedHead });
  return apiFetch<null>(`/api/me/skills/${skillId}/proposal?${search.toString()}`, {
    method: "DELETE",
  });
}

export function publishRelease(
  skillId: number,
  expectedHead: string,
  validatedHead: string,
  releaseNote: string,
) {
  return apiFetch<ReleaseSummary>(`/api/me/skills/${skillId}/releases`, {
    method: "POST",
    headers: { "Idempotency-Key": newIdempotencyKey() },
    body: JSON.stringify({ expectedHead, validatedHead, releaseNote }),
  });
}

export function listReleases(skillId: number) {
  return apiFetch<ReleaseSummary[]>(`/api/me/skills/${skillId}/releases`);
}

export function getRelease(skillId: number, releaseId: number) {
  return apiFetch<ReleaseDetail>(`/api/me/skills/${skillId}/releases/${releaseId}`);
}

export function compareReleases(skillId: number, fromReleaseId: number, toReleaseId: number) {
  const search = new URLSearchParams({
    from: String(fromReleaseId),
    to: String(toReleaseId),
  });
  return apiFetch<ReleaseCompare>(`/api/me/skills/${skillId}/compare?${search.toString()}`);
}

export function activateRelease(skillId: number, releaseId: number, expectedRevision: number) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}/releases/${releaseId}/activate`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision }),
  });
}

export function revokeRelease(skillId: number, releaseId: number, reason: string | null) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}/releases/${releaseId}/revoke`, {
    method: "POST",
    body: JSON.stringify({ reason }),
  });
}

export function setSkillEnabled(skillId: number, enabled: boolean, expectedRevision: number) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}/enabled`, {
    method: "PUT",
    body: JSON.stringify({ enabled, expectedRevision }),
  });
}

export function archiveSkill(skillId: number, expectedRevision: number) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}/archive`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision }),
  });
}

export function restoreSkill(skillId: number, expectedRevision: number) {
  return apiFetch<SkillSummary>(`/api/me/skills/${skillId}/restore`, {
    method: "POST",
    body: JSON.stringify({ expectedRevision }),
  });
}

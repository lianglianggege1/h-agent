import { ApiError, apiFetch } from "./http.ts";

/** Subagent Definition Catalog 管理接口客户端（设计 9）。 */

export type SubagentValidationIssue = {
  code: string;
  severity: "ERROR" | "WARNING" | null;
  field: string | null;
  line: number | null;
  column: number | null;
  message: string;
};

export type SubagentDefinitionSummary = {
  agentId: string;
  displayName: string;
  description: string;
  source: "BUILTIN" | "USER" | null;
  draftRevision: number | null;
  draftValid: boolean | null;
  currentVersion: number | null;
  enabled: boolean;
  deleted: boolean;
  updatedAt: string | null;
};

export type SubagentQuotaUsage = {
  maxDefinitions: number;
  maxEnabled: number;
  usedDefinitions: number;
  usedEnabled: number;
};

export type SubagentCapabilityInfo = {
  models: string[];
  defaultTools: string[];
  requestableTools: string[];
};

export type SubagentCatalogView = {
  system: SubagentDefinitionSummary[];
  mine: SubagentDefinitionSummary[];
  limits: SubagentQuotaUsage | null;
  capabilities: SubagentCapabilityInfo | null;
};

export type SubagentDefinitionDetail = {
  agentId: string;
  definitionId: number;
  source: "BUILTIN" | "USER" | null;
  currentVersion: number | null;
  currentMarkdown: string | null;
  currentContentHash: string | null;
  enabled: boolean;
  deleted: boolean;
  draftRevision: number | null;
  draftMarkdown: string | null;
  draftIssues: SubagentValidationIssue[];
  createdAt: string | null;
  updatedAt: string | null;
};

export type SubagentDraftResult = {
  agentId: string;
  definitionId: number;
  revision: number;
  issues: SubagentValidationIssue[];
};

export type SubagentValidationResult = {
  issues: SubagentValidationIssue[];
};

export type SubagentCapabilityDeclaration = {
  kind: "OMITTED" | "EMPTY" | "EXPLICIT" | null;
  names: string[] | null;
};

export type SubagentCompiledSummary = {
  displayName: string | null;
  description: string | null;
  mode: string | null;
  model: string | null;
  steps: number | null;
  tools: SubagentCapabilityDeclaration | null;
  skills: SubagentCapabilityDeclaration | null;
  workspaceMode: string | null;
  runtimeKind: string | null;
};

export type SubagentPublishResult = {
  agentId: string;
  definitionId: number;
  version: number;
  contentHash: string;
  enabled: boolean;
  revision: number;
  compiled: SubagentCompiledSummary | null;
};

export type SubagentVersionSummary = {
  version: number;
  contentHash: string;
  publishedAt: string | null;
  current: boolean;
};

export type SubagentVersionDetail = {
  agentId: string;
  definitionId: number;
  version: number;
  contentHash: string;
  markdown: string;
  publishedAt: string | null;
  current: boolean;
  compiled: SubagentCompiledSummary | null;
};

/** 设计 9.3 的接口错误码；revision conflict 需要保留本地文本。 */
export const SUBAGENT_ERROR_CODES = {
  INVALID_AGENT_ID: "INVALID_AGENT_ID",
  RESERVED_AGENT_ID: "RESERVED_AGENT_ID",
  DEFINITION_NOT_FOUND: "DEFINITION_NOT_FOUND",
  DEFINITION_ALREADY_EXISTS: "DEFINITION_ALREADY_EXISTS",
  DRAFT_REVISION_CONFLICT: "DRAFT_REVISION_CONFLICT",
  PUBLISH_VALIDATION_FAILED: "PUBLISH_VALIDATION_FAILED",
  NO_PUBLISHED_VERSION: "NO_PUBLISHED_VERSION",
  DEFINITION_LIMIT_EXCEEDED: "DEFINITION_LIMIT_EXCEEDED",
  ENABLED_LIMIT_EXCEEDED: "ENABLED_LIMIT_EXCEEDED",
  DELETE_REQUIRES_DISABLED: "DELETE_REQUIRES_DISABLED",
  DEFINITION_DELETED: "DEFINITION_DELETED",
} as const;

type SubagentErrorBody = {
  errorCode?: string;
  issues?: SubagentValidationIssue[];
};

export type SubagentCatalogError = {
  errorCode: string | null;
  issues: SubagentValidationIssue[];
};

/** 从 apiFetch 抛出的错误里提取 errorCode 与结构化 issues（设计 9.3）。 */
export function extractSubagentError(error: unknown): SubagentCatalogError {
  if (error instanceof ApiError) {
    const body = (error.data ?? null) as SubagentErrorBody | null;
    return {
      errorCode: body?.errorCode ?? null,
      issues: Array.isArray(body?.issues) ? body.issues : [],
    };
  }
  return { errorCode: null, issues: [] };
}

/** agentId 命名规则与后端 SubagentAgentIdRules 一致：kebab-case，1–63。 */
export function isValidSubagentAgentId(agentId: string) {
  return /^[a-z0-9]+(-[a-z0-9]+)*$/.test(agentId) && agentId.length <= 63;
}

/** 新建页的内置模板：字段与内置 researcher.md 同构。 */
export const SUBAGENT_TEMPLATE = `---
display_name: 我的 Subagent
description: 用一句话描述这个 Subagent 的职责边界
mode: subagent
model: inherit
steps: 12
tools: [read_file, grep_files, glob_files, list_files]
skills: []
workspace:
  mode: isolated
---

你是一名{{你的角色}} Subagent。

围绕父 Agent 委托的任务工作，不扩展任务范围：

- 先明确需要交付的结果，再开始执行；
- 结论必须给出依据，无法确认的信息如实说明；
- 最终输出按结论 → 依据 → 待确认事项的结构组织。
`;

export function listSubagentCatalog() {
  return apiFetch<SubagentCatalogView>("/api/me/subagents");
}

export function getSubagentDefinition(agentId: string) {
  return apiFetch<SubagentDefinitionDetail>(`/api/me/subagents/${encodeURIComponent(agentId)}`);
}

export function createSubagentDefinition(payload: { agentId: string; markdown: string }) {
  return apiFetch<SubagentDraftResult>("/api/me/subagents", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function saveSubagentDraft(agentId: string, payload: { expectedRevision: number; markdown: string }) {
  return apiFetch<SubagentDraftResult>(`/api/me/subagents/${encodeURIComponent(agentId)}/draft`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function validateSubagentMarkdown(markdown: string) {
  return apiFetch<SubagentValidationResult>("/api/me/subagents/validate", {
    method: "POST",
    body: JSON.stringify({ markdown }),
  });
}

export function publishSubagentDefinition(agentId: string, expectedRevision: number) {
  return apiFetch<SubagentPublishResult>(
    `/api/me/subagents/${encodeURIComponent(agentId)}/publish`,
    {
      method: "POST",
      body: JSON.stringify({ expectedRevision }),
    },
  );
}

export function setSubagentEnabled(agentId: string, enabled: boolean) {
  return apiFetch<SubagentDefinitionDetail>(`/api/me/subagents/${encodeURIComponent(agentId)}/enabled`, {
    method: "PUT",
    body: JSON.stringify({ enabled }),
  });
}

export function deleteSubagentDefinition(agentId: string) {
  return apiFetch<null>(`/api/me/subagents/${encodeURIComponent(agentId)}`, {
    method: "DELETE",
  });
}

export function restoreSubagentDefinition(agentId: string) {
  return apiFetch<SubagentDefinitionDetail>(`/api/me/subagents/${encodeURIComponent(agentId)}/restore`, {
    method: "POST",
    body: JSON.stringify({}),
  });
}

export function listSubagentVersions(agentId: string) {
  return apiFetch<SubagentVersionSummary[]>(`/api/me/subagents/${encodeURIComponent(agentId)}/versions`);
}

export function getSubagentVersionDetail(agentId: string, version: number) {
  return apiFetch<SubagentVersionDetail>(
    `/api/me/subagents/${encodeURIComponent(agentId)}/versions/${version}`,
  );
}

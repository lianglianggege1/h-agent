import { apiFetch } from "./http.ts";

export type ApprovalMode = "DEFAULT" | "ACCEPT_EDITS" | "EXPLORE" | "BYPASS" | "DONT_ASK";
export type ApprovalDecision = "APPROVE" | "DENY";
export type ApprovalStatus = "PENDING" | "APPROVED" | "DENIED" | "CANCELLED";

export type ApprovalAction = {
  toolCallId: string;
  toolName: string;
  summary: string;
};

export type ApprovalRequest = {
  approvalId: string;
  runId: number;
  rootSessionId: string;
  sessionId: string;
  subagentExecutionId: string | null;
  approvalMode: ApprovalMode;
  actions: ApprovalAction[];
  status: ApprovalStatus;
  decision: ApprovalDecision | null;
  version: number;
  requestedAt: string;
  decidedAt: string | null;
};

export const approvalModeOptions: Array<{
  value: ApprovalMode;
  label: string;
  description: string;
  tone: "safe" | "balanced" | "open";
}> = [
  { value: "DEFAULT", label: "标准审批", description: "敏感操作先询问，适合日常任务", tone: "balanced" },
  { value: "ACCEPT_EDITS", label: "自动接受编辑", description: "文件编辑自动执行，其他风险操作仍询问", tone: "balanced" },
  { value: "EXPLORE", label: "只读探索", description: "允许读取和分析，阻止修改性操作", tone: "safe" },
  { value: "DONT_ASK", label: "不弹出审批", description: "需要询问的操作直接拒绝", tone: "safe" },
  { value: "BYPASS", label: "完全放行", description: "跳过权限确认，仅用于可信环境", tone: "open" },
];

export function getPendingApproval(sessionId: string) {
  return apiFetch<ApprovalRequest | null>(
    `/api/chat/agent-sessions/${encodeURIComponent(sessionId)}/pending-approval`,
  );
}

export function approvalDecisionPath(approvalId: string) {
  return `/api/chat/approvals/${encodeURIComponent(approvalId)}/decision`;
}

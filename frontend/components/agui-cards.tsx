"use client";

import { createContext, useContext, useEffect, useState } from "react";
import { useCopilotKit } from "@copilotkit/react-core/v2";
import type { AbstractAgent } from "@ag-ui/client";
import { z } from "zod";
import { apiFetch } from "@/lib/http";
import { getPendingApproval, type ApprovalRequest, type ApprovalDecision } from "@/lib/harness-approval";
import {
  confirmAutomationProposal, discardAutomationProposal, listAutomationProposals, type AutomationProposal,
} from "@/lib/automations";
import type { ChatSessionMessage, ChatMessageResource } from "@/lib/chat-sessions";
import type { HarnessSubagentSummary } from "@/lib/harness-subagent-types";
import { ProgressCard, progressSchema, HistoricalReasoning } from "@/components/agui-progress";
import { MarkdownContent } from "@/components/markdown-content";
import { OrderCard, type OrderCardContent } from "@/components/order/order-card";

import { useFilePreview } from "./file-preview";
import type { PreviewResource } from "@/lib/file-preview";
import { DocumentTaskCard } from "@/components/document-panel";
import type { DocumentTask } from "@/lib/documents";

const statusLabels: Record<string, string> = {
  RUNNING: "执行中", WAITING_APPROVAL: "等待你的批准", COMPLETED: "已完成", FAILED: "执行失败",
  CANCELLED: "已停止", PAUSED: "已暂停", PAUSE_REQUESTED: "正在暂停", INTERRUPTED: "执行中断", BLOCKED: "请求被拦截", AVAILABLE: "可对话",
  RESUMED: "已继续执行", waiting: "等待批准",
  running: "执行中", started: "执行中", start: "执行中", completed: "已完成", end: "已完成",
  failed: "失败", cancelled: "已停止", delta: "执行中",
};

export function AutomationCard({ content }: { content: { proposal: AutomationProposal } }) {
  const [proposal, setProposal] = useState(content.proposal);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let disposed = false;
    const source = content.proposal;
    if (source.sourceSessionId) void listAutomationProposals(source.sourceSessionId).then((items) => {
      const current = items.find((item) => item.id === source.id);
      if (current && !disposed) setProposal(current);
    }).catch(() => {});
    return () => { disposed = true; };
  }, [content.proposal]);

  async function decide(confirm: boolean) {
    setBusy(true); setError("");
    try { setProposal(await (confirm ? confirmAutomationProposal(proposal.id) : discardAutomationProposal(proposal.id))); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "操作失败，请重试"); }
    finally { setBusy(false); }
  }
  const labels: Record<string, string> = { PENDING: "待确认", CONFIRMED: "已确认", DISCARDED: "已放弃", EXPIRED: "已过期" };
  return <article className="agui-card border-emerald-200 bg-emerald-50/70">
    <div className="flex justify-between gap-3"><span className="text-xs font-semibold text-emerald-700">自动化任务提案</span><span className="text-xs">{labels[proposal.status]}</span></div>
    <h3 className="mt-2 font-semibold">{proposal.name ?? "自动化任务"}</h3>
    {proposal.instruction ? <p className="mt-2 whitespace-pre-wrap leading-6">{proposal.instruction}</p> : null}
    <div className="mt-3 rounded-lg bg-white/70 p-3 text-xs leading-6 text-stone-600">
      <p>操作：{({ CREATE: "创建", UPDATE: "更新", ENABLE: "开启", DISABLE: "停用", DELETE: "删除" })[proposal.action]}</p>
      {proposal.cronExpression ? <p>计划：{proposal.cronExpression} · {proposal.zoneId}</p> : null}
      {proposal.upcomingFires?.length ? <p>下次执行：{new Date(proposal.upcomingFires[0]).toLocaleString("zh-CN", { timeZone: proposal.zoneId ?? "Asia/Shanghai" })}</p> : null}
    </div>
    {error ? <p role="alert" className="mt-2 text-red-700">{error}</p> : null}
    {proposal.status === "PENDING" ? <div className="mt-3 flex gap-2">
      <button className="agui-button" disabled={busy} onClick={() => void decide(false)}>放弃</button>
      <button className="agui-button agui-button-primary" disabled={busy} onClick={() => void decide(true)}>{busy ? "处理中…" : "确认"}</button>
    </div> : null}
  </article>;
}

export function ApprovalCard({ content, agent }: { content: { approval: ApprovalRequest }; agent: AbstractAgent | undefined }) {
  const { copilotkit } = useCopilotKit();
  const [current, setCurrent] = useState<ApprovalRequest | null>(content.approval);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let disposed = false;
    void getPendingApproval(content.approval.sessionId).then((value) => {
      if (!disposed) setCurrent(value?.approvalId === content.approval.approvalId ? value : null);
    }).catch(() => {});
    return () => { disposed = true; };
  }, [content.approval]);
  async function decide(decision: ApprovalDecision) {
    if (!agent || !current) return;
    setBusy(true); setError("");
    try {
      await copilotkit.runAgent({ agent, forwardedProps: { approvalId: current.approvalId, decision } });
      setCurrent(null);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "批准处理失败"); }
    finally { setBusy(false); }
  }
  return <article className="agui-card border-amber-200 bg-amber-50/80">
    <h3 className="font-semibold">{current ? "需要你的批准" : "批准请求已处理"}</h3>
    <ul className="mt-2 space-y-2">{content.approval.actions.map((action) => <li key={action.toolCallId} className="rounded-lg bg-white/70 p-2"><p className="font-medium">{action.toolName}</p><p className="mt-1 text-xs text-stone-600">{action.summary}</p></li>)}</ul>
    {error ? <p role="alert" className="mt-2 text-red-700">{error}</p> : null}
    {current ? <div className="mt-3 flex gap-2">
      <button className="agui-button" disabled={busy || !agent || agent.isRunning} onClick={() => void decide("DENY")}>拒绝</button>
      <button className="agui-button agui-button-primary" disabled={busy || !agent || agent.isRunning} onClick={() => void decide("APPROVE")}>{busy ? "处理中…" : "允许执行"}</button>
      <button className="agui-button" disabled={busy} onClick={() => {
        setBusy(true);
        void apiFetch("/api/chat/agui/threads/" + encodeURIComponent(content.approval.sessionId) + "/stop", { method: "POST" })
          .then(() => setCurrent(null)).catch((failure) => setError(failure instanceof Error ? failure.message : "停止失败"))
          .finally(() => setBusy(false));
      }}>停止任务</button>
    </div> : null}
  </article>;
}

export const ResourceReferenceContext = createContext<{
  selectedIds: ReadonlySet<string>;
  toggle: (resource: ChatMessageResource) => void;
} | null>(null);

/** Missing legacy metadata stays unknown; an actual empty file is displayed as 0 B. */
function formatResourceSize(bytes: number | null): string | null {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return null;
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index =
    bytes === 0
      ? 0
      : Math.max(
          0,
          Math.min(
            Math.floor(Math.log(bytes) / Math.log(1024)),
            units.length - 1,
          ),
        );
  const amount = (bytes / 1024 ** index).toLocaleString("zh-CN", {
    maximumFractionDigits: 1,
  });
  return `${amount} ${units[index]}`;
}

export function ResourceCard({
  content,
}: {
  content: { message: ChatSessionMessage; previewResources?: PreviewResource[] };
}) {
  const references = useContext(ResourceReferenceContext);
  const preview = useFilePreview();
  const resources = content.previewResources ?? content.message.resources ?? [];
  const generating = Boolean(content.message.payload?.status && !["SUCCEEDED", "COMPLETED", "READY"].includes(content.message.payload.status) && content.message.resources?.some(item => item.role === "GENERATED"));
  if (
    !content.message.resources?.length &&
    content.message.payload?.status === "SUCCEEDED"
  )
    return null;
  return (
    <article className="agui-card space-y-3">
      {content.message.payload?.status ? (
        <p className="text-xs text-stone-500">
          生成状态：{content.message.payload.status}
        </p>
      ) : null}
      {content.message.resources?.map((resource) => {
        const selected = references?.selectedIds.has(resource.id) ?? false;
        const isMedia = /^(image|video|audio)\//.test(resource.mimeType);
        const extension = resource.fileName
          .match(/\.([a-z0-9]{1,12})$/i)?.[1]
          .toUpperCase();
        const fileType =
          resource.mimeType === "application/pdf"
            ? "PDF"
            : (extension ?? "文件");
        const fileSize = formatResourceSize(resource.fileSize);
        return (
          <div key={resource.id} className="space-y-2">
            {resource.mimeType.startsWith("image/") ? (
              <a href={resource.viewUrl} target="_blank" rel="noreferrer" onClick={(event) => { if (preview) { event.preventDefault(); if (!generating) preview.open(resources, resource.id); } }}>
                <picture>
                  <img
                    src={resource.viewUrl}
                    alt={resource.fileName}
                    className="max-h-96 rounded-xl object-contain"
                  />
                </picture>
              </a>
            ) : resource.mimeType.startsWith("video/") ? (
              <video
                src={resource.viewUrl}
                controls
                className="max-h-96 rounded-xl"
              />
            ) : resource.mimeType.startsWith("audio/") ? (
              <audio src={resource.viewUrl} controls className="w-full" />
            ) : null}
            <div className="flex items-center gap-3">
              {!isMedia ? (
                <>
                  <span
                    className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-500"
                    aria-hidden="true"
                  >
                    <svg
                      width="22"
                      height="22"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.7"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" />
                      <path d="M14 2v6h6M8 13h8M8 17h5" />
                    </svg>
                  </span>
                  <div className="min-w-0 flex-1">
                    <p
                      className="break-all text-sm font-medium text-stone-800"
                      title={resource.fileName}
                    >
                      {resource.fileName || "未命名文件"}
                    </p>
                    <p className="mt-1 text-xs text-stone-500">
                      {[fileType, fileSize].filter(Boolean).join(" · ")}
                    </p>
                  </div>
                </>
              ) : null}
              <button type="button" className="agui-button text-xs" aria-label={`预览 ${resource.fileName}`} disabled={generating} onClick={() => preview?.open(resources, resource.id)}>预览</button>
              <a
                href={resource.downloadUrl}
                download={resource.fileName}
                aria-label={`下载 ${resource.fileName}`}
                title={`下载 ${resource.fileName}`}
                className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-stone-500 transition-colors hover:bg-stone-100 hover:text-stone-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-400"
              >
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.7"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <path d="M12 3v12m-5-5 5 5 5-5M5 15v5h14v-5" />
                </svg>
              </a>
              {references ? (
                <button
                  type="button"
                  aria-pressed={selected}
                  aria-label={`${selected ? "取消引用" : "引用"} ${resource.fileName}`}
                  title={selected ? "取消引用" : "引用素材"}
                  onClick={() => references.toggle(resource)}
                  className={`inline-flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-xs transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-400 ${selected ? "bg-indigo-50 text-indigo-600 hover:bg-indigo-100" : "text-stone-500 hover:bg-stone-100 hover:text-stone-800"}`}
                >
                  <svg
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    aria-hidden="true"
                  >
                    <path
                      d={
                        selected
                          ? "m5 12 4 4 10-10"
                          : "m9 5-5 5 5 5M4 10h9a7 7 0 0 1 7 7v2"
                      }
                    />
                  </svg>
                  {selected ? "已引用" : "引用"}
                </button>
              ) : null}
            </div>
          </div>
        );
      })}
    </article>
  );
}

function SubagentCard({ content }: { content: { subagent: HarnessSubagentSummary } }) {
  const child = content.subagent;
  return <div className="agui-card"><div className="flex justify-between"><strong>{child.displayName}</strong><span className="text-xs text-stone-500">{statusLabels[child.status] ?? child.status}</span></div><p className="mt-2 text-xs leading-5 text-stone-600">{child.assignment}</p></div>;
}

export const activityRenderers = [
  { activityType: "h-agent.document", content: z.object({ task: z.custom<DocumentTask>() }), render: DocumentTaskCard },
  { activityType: "h-agent.order", content: z.custom<OrderCardContent>(), render: OrderCard },
  { activityType: "h-agent.ticket", content: z.custom<OrderCardContent>(), render: OrderCard },
  { activityType: "h-agent.order-decision", content: z.custom<OrderCardContent>(), render: OrderCard },
  { activityType: "h-agent.progress", content: progressSchema, render: ProgressCard },
  { activityType: "h-agent.automation", content: z.object({ proposal: z.custom<AutomationProposal>() }), render: AutomationCard },
  { activityType: "h-agent.approval", content: z.object({ approval: z.custom<ApprovalRequest>() }), render: ApprovalCard },
  { activityType: "h-agent.resource", content: z.object({ message: z.custom<ChatSessionMessage>() }), render: ResourceCard },
  { activityType: "h-agent.subagent", content: z.object({ subagent: z.custom<HarnessSubagentSummary>() }), render: SubagentCard },
  { activityType: "h-agent.reasoning", content: z.object({ text: z.string() }), render: HistoricalReasoning },
  { activityType: "h-agent.assignment", content: z.object({ text: z.string() }), render: ({ content }: { content: { text: string } }) => <article className="agui-card"><h3 className="text-xs font-semibold text-amber-800">父 Agent 的委托</h3><p className="mt-2 whitespace-pre-wrap leading-6">{content.text}</p></article> },
];

export function ToolResult({ name, status, result }: { name: string; status: string; result: string | undefined }) {
  return <details className="agui-card"><summary className="cursor-pointer">{name} <span className="ml-2 text-xs text-stone-500">{status === "complete" ? "已完成" : "执行中"}</span></summary>{result ? <div className="mt-3 max-h-80 overflow-auto"><MarkdownContent content={result} /></div> : null}</details>;
}

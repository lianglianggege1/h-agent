"use client";

import { useEffect, useId, useState } from "react";
import { z } from "zod";

export const progressSchema = z.object({
  runId: z.string(), status: z.string(), reasoning: z.string().default(""),
  answerStarted: z.boolean().default(false), startedAt: z.number().optional(), processEndedAt: z.number().optional(),
  elapsedBeforeMs: z.number().nonnegative().optional(),
  entries: z.array(z.object({
    id: z.string(), type: z.enum(["reasoning", "step"]), chunks: z.array(z.string()).optional(), stepId: z.string().optional(),
  })).default([]),
  steps: z.array(z.object({
    id: z.string(), name: z.string(), status: z.string(), result: z.string().optional(), kind: z.string().optional(),
  })),
});

type ProgressContent = z.infer<typeof progressSchema>;
const running = (status: string) => ["running", "start", "started"].includes(status.toLowerCase());
const failed = (status: string) => ["failed", "error", "denied"].includes(status.toLowerCase());
const toolNames: Record<string, string> = { web_search: "搜索资料", web_fetch: "读取网页" };
const statusLabels: Record<string, string> = {
  WAITING_APPROVAL: "等待确认", FAILED: "执行出错", CANCELLED: "已停止", INTERRUPTED: "执行中断", BLOCKED: "请求被拦截",
};
const failureMessages: Record<string, string> = {
  FAILED: "执行遇到问题，请稍后重试。", INTERRUPTED: "执行服务已中断，请重新发起任务。", BLOCKED: "当前请求未通过检查，请调整后重试。",
};

export function ProgressCard({ content }: { content: ProgressContent }) {
  const bodyId = useId();
  const [choice, setChoice] = useState<{ runId: string; open: boolean } | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const active = content.status === "RUNNING";
  const timing = active && content.startedAt !== undefined && content.processEndedAt === undefined;
  useEffect(() => {
    if (!timing) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [timing]);

  const open = choice?.runId === content.runId ? choice.open : active && !content.answerStarted;
  const steps = content.steps;
  const entries = content.entries ?? [];
  const hasReasoning = Boolean(content.reasoning) || entries.some((entry) => entry.type === "reasoning");
  const current = steps.findLast((step) => running(step.status));
  const toolCount = steps.filter((step) => step.kind === "tool" || step.id.startsWith("tool-")).length;
  const seconds = content.startedAt === undefined || !active && content.processEndedAt === undefined ? null
    : Math.round(((content.elapsedBeforeMs ?? 0) + Math.max(0, (content.processEndedAt ?? now) - content.startedAt)) / 1000);
  const duration = seconds === null ? "" : `${seconds} 秒`;
  let title = statusLabels[content.status];
  if (!title) {
    if (active && current) title = `${toolNames[current.name] ?? (current.kind === "tool" ? "正在使用工具" : current.name)}…`;
    else if (active && !content.answerStarted) title = "正在思考…";
    else title = hasReasoning ? (duration ? `思考了 ${duration}` : "思考过程") : (duration ? `处理了 ${duration}` : "查看过程");
  }
  const error = ["FAILED", "INTERRUPTED", "BLOCKED"].includes(content.status)
    ? steps.findLast((step) => failed(step.status) && step.result)?.result ?? failureMessages[content.status] : undefined;
  const shownSteps = new Set(entries.filter((entry) => entry.type === "step").map((entry) => entry.stepId));
  const timeline = [
    ...(entries.length ? entries : content.reasoning ? [{ id: "legacy", type: "reasoning" as const, chunks: [content.reasoning] }] : []),
    ...steps.filter((step) => !shownSteps.has(step.id)).map((step) => ({ id: step.id, type: "step" as const, stepId: step.id })),
  ];
  if (!active && !statusLabels[content.status] && !timeline.length) return null;

  return <section className="agui-progress" aria-label="思考与执行过程">
    <button type="button" className="agui-progress-toggle" aria-expanded={open} aria-controls={bodyId}
      onClick={() => setChoice({ runId: content.runId, open: !open })}>
      <svg className="agui-progress-chevron" data-open={open} viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true"><path d="m6 4 4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
      {active && (!content.answerStarted || current) ? <span className="agui-progress-dot agui-pulse" aria-hidden="true" /> : null}
      <span>{title}</span>
      {toolCount > 0 ? <span className="agui-progress-meta">· 调用工具 {toolCount} 次</span> : null}
      {timing && !content.answerStarted ? <span className="agui-progress-meta">{duration}</span> : null}
    </button>
    {error ? <p role="alert" className="agui-progress-error">{error}</p> : null}
    <div id={bodyId} hidden={!open} className="agui-progress-body">
      <ol className="agui-progress-timeline">
        {timeline.map((entry) => {
          if (entry.type === "reasoning") return <li key={entry.id} className="agui-progress-reasoning">{entry.chunks?.join("")}</li>;
          const step = steps.find((item) => item.id === entry.stepId);
          if (!step) return null;
          const phase = step.status.toLowerCase();
          const label = running(phase) ? "进行中" : failed(phase) ? "失败" : phase === "waiting" ? "等待确认"
            : phase === "cancelled" || phase === "interrupted" ? "已停止" : "完成";
          return <li key={entry.id} className="agui-progress-step" data-failed={failed(phase)}>
            <details>
              <summary><span>{toolNames[step.name] ?? step.name}</span><span className="agui-progress-meta">{label}</span></summary>
              {step.result ? <pre className="agui-progress-result">{step.result}</pre> : <p className="agui-progress-meta">{running(phase) ? "等待执行结果…" : "无额外结果"}</p>}
            </details>
          </li>;
        })}
        {!timeline.length ? <li className="agui-progress-meta">正在准备…</li> : null}
      </ol>
    </div>
  </section>;
}

/** Pre-AG-UI reasoning uses the same compact, collapsed presentation. */
export function HistoricalReasoning({ content }: { content: { text: string } }) {
  return <ProgressCard content={{ runId: "historical", status: "COMPLETED", answerStarted: true,
    reasoning: content.text, entries: [], steps: [] }} />;
}

"use client";

import Link from "next/link";
import { listChatHistory, type ChatSessionSummary } from "@/lib/chat-sessions";
import { useEffect, useState } from "react";
import { automationRunHref, editAutomationProposal, getAutomationTrial, tryAutomationProposal,
  type AutomationProposal, type AutomationTrial } from "@/lib/automations";

export function ProposalControls({ proposal, onSaved }: {
  proposal: AutomationProposal; onSaved: (next: AutomationProposal) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [instruction, setInstruction] = useState(proposal.instruction ?? "");
  const [cron, setCron] = useState(proposal.cronExpression ?? "");
  const [zone, setZone] = useState(proposal.zoneId ?? "");
  const [session, setSession] = useState(proposal.executionSessionId ?? proposal.sourceSessionId ?? "");
  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [trial, setTrial] = useState<AutomationTrial | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const activeRun = trial && ["QUEUED", "RUNNING"].includes(trial.status) ? trial.id : null;

  useEffect(() => {
    if (!activeRun) return;
    const lifetime = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll(id: string) {
      try {
        const result = await getAutomationTrial(id, lifetime.signal);
        if (!lifetime.signal.aborted) {
          setTrial(result);
          if (["QUEUED", "RUNNING"].includes(result.status)) timer = setTimeout(() => void poll(id), 2000);
        }
      } catch (failure) {
        if (!lifetime.signal.aborted) setError(failure instanceof Error ? failure.message : "试跑状态读取失败");
      }
    }
    void poll(activeRun);
    return () => { lifetime.abort(); clearTimeout(timer); };
  }, [activeRun]);

  useEffect(() => {
    if (!editing || proposal.action !== "CREATE") return;
    let current = true;
    void listChatHistory(0, 100).then((options) => { if (current) setSessions(options); }).catch(() => {});
    return () => { current = false; };
  }, [editing, proposal.action]);

  const revision = proposal.updatedAt;
  if (proposal.status !== "PENDING" || !revision || !["CREATE", "UPDATE"].includes(proposal.action)) return null;

  async function act(action: "save" | "trial") {
    if (!revision) return;
    setBusy(true);
    setError("");
    try {
      if (action === "trial") setTrial(await tryAutomationProposal(proposal.id, revision));
      else {
        const updated = await editAutomationProposal(proposal.id,
          { revision, instruction, cronExpression: cron, zoneId: zone, executionSessionId: session });
        setEditing(false);
        onSaved(updated);
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "提案操作失败");
    } finally {
      setBusy(false);
    }
  }

  return <section className="mt-3 space-y-2">
    {editing ? <div className="space-y-2 rounded-lg border border-stone-200 bg-white p-3 text-sm">
      <label className="block">执行指令<textarea className="mt-1 w-full rounded border border-stone-200 p-2" rows={3}
        value={instruction} onChange={(event) => setInstruction(event.target.value)} /></label>
      {proposal.action === "CREATE" ? <label className="block">执行会话
        <select value={session} onChange={(event) => setSession(event.target.value)}
          className="ml-2 max-w-[70%] rounded border border-stone-200 p-1">
          {sessions.some((option) => option.sessionId === session) ? null : <option value={session}>当前提案会话</option>}
          {sessions.map((option) => <option key={option.sessionId} value={option.sessionId}>{option.title}</option>)}
        </select>
      </label> : null}
      <label className="block">运行计划（Cron）<input className="ml-2 rounded border border-stone-200 p-1" value={cron}
        onChange={(event) => setCron(event.target.value)} /></label>
      <label className="block">时区<input className="ml-2 rounded border border-stone-200 p-1" value={zone}
        onChange={(event) => setZone(event.target.value)} /></label>
      <div className="flex gap-2">
        <button type="button" disabled={busy} className="agui-button" onClick={() => setEditing(false)}>取消编辑</button>
        <button type="button" disabled={busy} className="agui-button agui-button-primary" onClick={() => void act("save")}>保存修改</button>
      </div>
    </div> : <div className="flex gap-2">
      <button type="button" disabled={busy} className="agui-button" onClick={() => setEditing(true)}>编辑提案</button>
      <button type="button" disabled={busy || !!activeRun} className="agui-button" onClick={() => void act("trial")}>试跑当前提案</button>
    </div>}
    {trial ? <div className="rounded-lg bg-white p-3 text-sm">
      <p>{({ QUEUED: "试跑排队中", RUNNING: "试跑中", SUCCEEDED: "试跑完成", FAILED: "试跑失败" })[trial.status] ?? trial.status}</p>
      {trial.output || trial.failure ? <p className="mt-1 whitespace-pre-wrap">{trial.output ?? trial.failure}</p> : null}
      <Link href={automationRunHref(trial.id)} className="mt-1 inline-block underline">查看本次运行</Link>
    </div> : null}
    {error ? <p role="alert" className="text-sm text-red-700">{error}</p> : null}
  </section>;
}

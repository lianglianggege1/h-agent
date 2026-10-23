"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { CopilotKit } from "@copilotkit/react-core/v2";
import { AguiChatThread } from "@/components/agui-chat-thread";
import { activityRenderers } from "@/components/agui-cards";
import { getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { listSystemPrompts, type SystemPrompt } from "@/lib/system-prompts";
import { listAgents, type AgentSummary } from "@/lib/agents";
import {
  activateHistorySession, bootstrapChatSession, createChatSession, getChatSession,
  listChatHistory, resolveChatSession, type ChatSessionOpen, type ChatSessionSummary,
} from "@/lib/chat-sessions";
import { getHarnessSubagents } from "@/lib/harness-subagent-api";
import type { HarnessSubagentSummary } from "@/lib/harness-subagent-types";
import { approvalModeOptions, type ApprovalMode } from "@/lib/harness-approval";
import { buildCallHref } from "@/lib/voice-call";

const standardId = "standard-chat";
const harnessId = "harness-agent";
const childStatus: Record<string, string> = { AVAILABLE: "可对话", RUNNING: "执行中", COMPLETED: "已完成", FAILED: "失败" };

function ChatWorkspace() {
  const router = useRouter();
  const search = useSearchParams();
  const requestedSession = search.get("sessionId");
  const requestedAgent = search.get("agentId");
  const [open, setOpen] = useState<ChatSessionOpen | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [catalog, setCatalog] = useState<AgentSummary[]>([]);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [candidates, setCandidates] = useState<ChatSessionSummary[]>([]);
  const [history, setHistory] = useState<ChatSessionSummary[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [moreHistory, setMoreHistory] = useState(true);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [agentQuery, setAgentQuery] = useState("");
  const [domain, setDomain] = useState("全部");
  const [approvalMode, setApprovalMode] = useState<ApprovalMode>("DEFAULT");
  const [creating, setCreating] = useState(false);
  const [children, setChildren] = useState<HarnessSubagentSummary[]>([]);
  const [selectedChild, setSelectedChild] = useState<string | null>(null);
  const currentSession = useRef<ChatSessionOpen | null>(null);
  const sessionId = open?.session.sessionId ?? null;
  const harness = open?.session.runtimeType === "HARNESS_STREAMING";
  const standard = open?.session.agentId === standardId;

  const hydrate = useCallback((detail: ChatSessionOpen) => {
    currentSession.current = detail;
    setOpen(detail); setChildren(detail.subagents ?? []); setSelectedChild(null); setCandidates([]);
    setError("");
  }, []);

  useEffect(() => {
    let disposed = false;
    if (requestedSession && currentSession.current?.session.sessionId === requestedSession) return;
    setLoading(true);
    void getCurrentUser().then(async () => {
      const [agents, promptList] = await Promise.all([listAgents(), listSystemPrompts()]);
      if (disposed) return;
      setCatalog(agents); setPrompts(promptList);
      const bootstrap = await bootstrapChatSession();
      if (disposed) return;
      let detail = bootstrap.session;
      if (requestedSession) {
        detail = await activateHistorySession(requestedSession, currentSession.current?.session.sessionId);
      } else if (requestedAgent && detail?.session.agentId !== requestedAgent) {
        detail = await createChatSession({ agentId: requestedAgent, approvalMode: requestedAgent === harnessId ? "DEFAULT" : null });
      } else if (bootstrap.resolution === "choose") {
        setCandidates(bootstrap.candidates);
      }
      if (!disposed && detail) hydrate(detail);
    }).catch((failure) => {
      if (disposed) return;
      if (failure instanceof Error && failure.message === "Unauthorized") {
        savePostLoginRedirect("/chat"); router.replace("/auth/login");
      } else setError(failure instanceof Error ? failure.message : "加载会话失败");
    }).finally(() => { if (!disposed) setLoading(false); });
    return () => { disposed = true; };
  }, [requestedSession, requestedAgent, hydrate, router]);

  const refreshChildren = useCallback(async () => {
    if (!sessionId || !harness) return;
    try { setChildren(await getHarnessSubagents(sessionId)); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "协作状态加载失败"); }
  }, [sessionId, harness]);

  useEffect(() => {
    if (!harness || !sessionId) return;
    const timer = window.setInterval(() => void refreshChildren(), 3000);
    return () => clearInterval(timer);
  }, [sessionId, harness, refreshChildren]);

  const afterTurn = useCallback(() => {
    void refreshChildren();
    if (sessionId) void getChatSession(sessionId).then((session) => {
      setOpen((previous) => previous?.session.sessionId === sessionId ? { ...previous, session } : previous);
    }).catch(() => {});
  }, [sessionId, refreshChildren]);

  function chatHref(id: string) {
    const params = new URLSearchParams({ sessionId: id });
    if (process.env.NODE_ENV === "development" && search.get("debug") === "1") params.set("debug", "1");
    return "/chat?" + params.toString();
  }

  async function choose(id: string) {
    setCreating(true);
    try {
      const detail = await resolveChatSession(id);
      hydrate(detail); router.replace(chatHref(detail.session.sessionId), { scroll: false });
    } catch (failure) { setError(failure instanceof Error ? failure.message : "恢复会话失败"); }
    finally { setCreating(false); }
  }

  async function create(agentId: string, promptId?: number) {
    if (creating) return;
    setCreating(true);
    try {
      const detail = await createChatSession({ currentSessionId: sessionId, agentId, promptId: promptId ?? null,
        approvalMode: agentId === harnessId ? approvalMode : null });
      hydrate(detail); setPickerOpen(false); setHistoryOpen(false);
      router.replace(chatHref(detail.session.sessionId), { scroll: false });
    } catch (failure) { setError(failure instanceof Error ? failure.message : "创建会话失败"); }
    finally { setCreating(false); }
  }

  async function loadHistory(reset: boolean) {
    if (historyBusy) return;
    setHistoryBusy(true);
    try {
      const page = reset ? 0 : historyPage;
      const items = await listChatHistory(page, 15);
      setHistory((previous) => reset ? items : [...previous, ...items]); setHistoryPage(page + 1); setMoreHistory(items.length === 15);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "历史加载失败"); }
    finally { setHistoryBusy(false); }
  }

  async function activate(id: string) {
    setCreating(true);
    try {
      const detail = await activateHistorySession(id, sessionId);
      hydrate(detail); setHistoryOpen(false);
      router.replace(chatHref(id), { scroll: false });
    } catch (failure) { setError(failure instanceof Error ? failure.message : "打开会话失败"); }
    finally { setCreating(false); }
  }

  const selected = children.find((child) => child.sessionId === selectedChild);
  const directChildren = children.filter((child) => child.parentSessionId === sessionId);
  const domains = ["全部", ...new Set(catalog.filter((agent) => agent.runtimeType === "AGENTIC_SYNC").map((agent) => agent.domain))];
  const filtered = catalog.filter((agent) => agent.enabled && agent.agentId !== standardId && agent.agentId !== harnessId
    && (domain === "全部" || agent.domain === domain)
    && [agent.displayName, agent.summary, agent.domain, ...agent.tags].join(" ").toLowerCase().includes(agentQuery.toLowerCase()));

  return <main className="mx-auto flex h-dvh w-full max-w-md flex-col overflow-hidden bg-[#f7f4ea] text-stone-900">
    <header className="z-10 shrink-0 border-b border-stone-200 bg-[#f7f4ea]/95 px-4 py-3">
      <div className="mx-auto flex max-w-5xl items-center gap-3">
        <button className="agui-button" aria-label="打开历史会话" onClick={() => { setHistoryOpen(true); void loadHistory(true); }}>☰</button>
        <div className="min-w-0 flex-1"><h1 className="truncate font-semibold">{open?.session.title ?? "H Agent"}</h1><p className="mt-0.5 text-xs text-stone-500">{open?.session.agentDisplayName ?? "选择会话开始"}</p></div>
        {open && !standard ? <Link className="hidden text-xs text-amber-800 sm:block" href={"/me/agents/" + encodeURIComponent(open.session.agentId)}>拓扑详情</Link> : null}
        {standard && sessionId ? <Link className="agui-button" href={buildCallHref(sessionId)}>语音</Link> : null}
        <button className="agui-button agui-button-primary" onClick={() => setPickerOpen(true)}>新会话</button>
      </div>
      {standard ? <div className="mx-auto mt-3 flex max-w-5xl items-center gap-3 text-xs">
        <select aria-label="选择 SystemPrompt" className="min-w-0 flex-1 rounded-xl border border-stone-200 bg-white px-3 py-2" value={open?.session.promptId ?? ""}
          onChange={(event) => void create(standardId, Number(event.target.value))} disabled={creating}>
          <option value="" disabled>选择提示词</option>{prompts.map((prompt) => <option key={prompt.id} value={prompt.id}>{prompt.name}</option>)}
        </select>
        <Link className="text-amber-800" href={"/me/knowledge?promptId=" + (open?.session.promptId ?? "")}>知识库</Link>
        <Link className="text-amber-800" href="/me/system-prompts">管理</Link>
      </div> : null}
      {harness && directChildren.length ? <div className="mx-auto mt-3 flex max-w-5xl gap-2 overflow-x-auto pb-1" aria-label="协作进度">
        {directChildren.map((child) => <button key={child.sessionId} className="shrink-0 rounded-xl border border-stone-200 bg-white px-3 py-2 text-left text-xs" onClick={() => setSelectedChild(child.sessionId)}>
          <span className={child.status === "RUNNING" ? "agui-pulse text-amber-600" : "text-emerald-600"}>●</span> {child.displayName}<span className="ml-2 text-stone-500">{childStatus[child.status]}</span>
        </button>)}
      </div> : null}
    </header>
    {error ? <div role="alert" className="mx-4 mt-2 rounded-xl bg-red-50 p-3 text-sm text-red-700">{error}</div> : null}
    {loading ? <div className="grid flex-1 place-items-center text-sm text-stone-500">加载会话…</div>
      : sessionId ? <div className="mx-auto flex min-h-0 w-full max-w-5xl flex-1 flex-col"><AguiChatThread key={sessionId} threadId={sessionId} onChanged={afterTurn} /></div>
      : <div className="grid flex-1 place-items-center"><button className="agui-button agui-button-primary" onClick={() => setPickerOpen(true)}>开始新会话</button></div>}

    {selected && sessionId ? <div className="fixed inset-0 z-30 flex justify-end bg-stone-950/25">
      <button className="absolute inset-0" aria-label="关闭子 Agent 对话" onClick={() => setSelectedChild(null)} />
      <aside className="relative flex h-full w-[92%] max-w-md flex-col bg-[#f7f4ea] shadow-2xl">
        <header className="shrink-0 border-b border-stone-200 p-4">
          <div className="flex items-center justify-between"><div><h2 className="font-semibold">{selected.displayName}</h2><p className="mt-1 text-xs text-stone-500">{childStatus[selected.status]} · 关闭面板后任务继续执行</p></div><button className="agui-button" onClick={() => setSelectedChild(null)}>关闭</button></div>
          {selected.parentSessionId !== sessionId ? <button className="mt-2 text-xs text-amber-800 underline" onClick={() => setSelectedChild(selected.parentSessionId)}>返回上级 Agent</button> : null}
          <div className="mt-3 flex gap-2 overflow-auto">{children.filter((child) => child.parentSessionId === selected.sessionId).map((child) => <button key={child.sessionId} className="agui-button shrink-0" onClick={() => setSelectedChild(child.sessionId)}>{child.displayName} · {childStatus[child.status]}</button>)}</div>
        </header>
        <AguiChatThread key={selected.sessionId} threadId={selected.sessionId} onChanged={afterTurn} />
      </aside>
    </div> : null}

    {historyOpen ? <div className="fixed inset-0 z-40 flex bg-stone-950/25">
      <button className="absolute inset-0" aria-label="关闭会话列表" onClick={() => setHistoryOpen(false)} />
      <aside className="relative flex h-full w-[88%] max-w-sm flex-col bg-[#f7f4ea] p-4 shadow-2xl">
        <div className="flex justify-between"><h2 className="font-semibold">历史会话</h2><button className="text-sm" onClick={() => setHistoryOpen(false)}>关闭</button></div>
        <div className="mt-4 flex-1 space-y-2 overflow-y-auto">{history.map((item) => <button key={item.sessionId} disabled={creating} onClick={() => void activate(item.sessionId)} className="w-full rounded-xl border border-stone-200 bg-white p-3 text-left">
          <p className="truncate text-sm font-medium">{item.title}</p><p className="mt-1 truncate text-xs text-stone-500">{item.agentDisplayName} · {item.lastUserMessage}</p>
        </button>)}{moreHistory ? <button disabled={historyBusy} className="agui-button w-full" onClick={() => void loadHistory(false)}>{historyBusy ? "加载中…" : "加载更多"}</button> : null}</div>
        <nav className="mt-4 flex flex-wrap gap-4 border-t border-stone-200 pt-4 text-sm"><Link href="/me">个人中心</Link><Link href="/automations">自动化任务</Link><button onClick={() => { void logout().then(() => router.replace("/auth/login")).catch((failure) => setError(failure instanceof Error ? failure.message : "退出失败")); }}>退出登录</button></nav>
      </aside>
    </div> : null}

    {pickerOpen ? <div className="fixed inset-0 z-50 grid place-items-center bg-stone-950/30 p-4">
      <button className="absolute inset-0" aria-label="关闭新会话选择" onClick={() => setPickerOpen(false)} />
      <section className="relative max-h-[88dvh] w-full max-w-lg overflow-y-auto rounded-3xl bg-[#f7f4ea] p-5 shadow-2xl">
        <div className="flex justify-between"><h2 className="text-lg font-semibold">选择新会话</h2><button disabled={creating} onClick={() => setPickerOpen(false)}>关闭</button></div>
        <button disabled={creating} className="mt-4 w-full rounded-2xl border border-stone-200 bg-white p-4 text-left" onClick={() => void create(standardId, prompts.find((prompt) => prompt.isDefault)?.id ?? prompts[0]?.id)}><strong>通用助手</strong><p className="mt-1 text-xs text-stone-500">自由对话，使用提示词、知识库和常用工具</p></button>
        <div className="mt-3 rounded-2xl border border-amber-200 bg-amber-50 p-4"><strong>协作 Agent</strong><p className="mt-1 text-xs text-stone-600">拆分复杂任务，查看协作者进度和工具执行过程</p>
          <select aria-label="批准模式" className="mt-3 w-full rounded-xl border border-amber-200 bg-white p-2 text-sm" value={approvalMode} onChange={(event) => setApprovalMode(event.target.value as ApprovalMode)}>{approvalModeOptions.map((mode) => <option key={mode.value} value={mode.value}>{mode.label}</option>)}</select>
          <p className="mt-2 text-xs text-stone-500">{approvalModeOptions.find((mode) => mode.value === approvalMode)?.description}</p>
          <button disabled={creating} className="agui-button agui-button-primary mt-3 w-full" onClick={() => void create(harnessId)}>开始协作</button>
        </div>
        <h3 className="mt-5 font-semibold">领域 Agent</h3>
        <input className="mt-2 w-full rounded-xl border border-stone-200 bg-white p-3 text-sm" placeholder="搜索名称、领域或说明" aria-label="搜索领域 Agent" value={agentQuery} onChange={(event) => setAgentQuery(event.target.value)} />
        <div className="mt-2 flex gap-2 overflow-x-auto">{domains.map((item) => <button key={item} className={"agui-button shrink-0 " + (domain === item ? "agui-button-primary" : "")} onClick={() => setDomain(item)}>{item}</button>)}</div>
        <div className="mt-3 space-y-2">{filtered.map((agent) => <button key={agent.agentId} disabled={creating} className="w-full rounded-xl border border-stone-200 bg-white p-3 text-left" onClick={() => void create(agent.agentId)}><p className="text-sm font-semibold">{agent.displayName} <span className="ml-2 text-xs font-normal text-amber-800">{agent.domain}</span></p><p className="mt-1 text-xs leading-5 text-stone-500">{agent.summary}</p></button>)}{!filtered.length ? <p className="py-4 text-center text-sm text-stone-500">没有匹配的 Agent</p> : null}</div>
      </section>
    </div> : null}

    {candidates.length ? <div className="fixed inset-0 z-40 grid place-items-center bg-stone-950/30 p-4"><section className="w-full max-w-md rounded-3xl bg-[#f7f4ea] p-5"><h2 className="font-semibold">继续哪个会话？</h2><div className="mt-4 max-h-[65dvh] space-y-2 overflow-y-auto">{candidates.map((item) => <button key={item.sessionId} disabled={creating} className="agui-button w-full text-left" onClick={() => void choose(item.sessionId)}>{item.title} · {item.agentDisplayName}</button>)}</div></section></div> : null}
  </main>;
}

function ChatRuntime() {
  const search = useSearchParams();
  const enableInspector = process.env.NODE_ENV === "development" && search.get("debug") === "1";
  return <CopilotKit runtimeUrl="/api/copilotkit" useSingleEndpoint={false} credentials="include"
      renderActivityMessages={activityRenderers} enableInspector={enableInspector}>
      <ChatWorkspace />
    </CopilotKit>;
}

export default function ChatPage() {
  return <Suspense fallback={<main className="min-h-dvh bg-[#f7f4ea]" />}>
    <ChatRuntime />
  </Suspense>;
}

"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { AgentSummary, listAgents } from "@/lib/agents";
import { getCurrentUser } from "@/lib/auth";
import {
  applyAgentStep,
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  toRenderableTurns,
  toUiChatMessage,
  type UiAgentStep,
  type UiChatMessage,
} from "@/lib/chat-message-state";
import { createChatSession } from "@/lib/chat-sessions";
import { apiStream } from "@/lib/http";
import { savePostLoginRedirect } from "@/lib/session";

function statusText(status: UiAgentStep["status"]) {
  if (status === "running") return "执行中";
  if (status === "completed") return "已完成";
  return "失败";
}

function AgentStepDetails({ steps, pending = false }: { steps: UiAgentStep[]; pending?: boolean }) {
  if (steps.length === 0) {
    return null;
  }

  return (
    <details
      className="rounded-lg border border-stone-200 bg-stone-50 px-3 py-2 text-stone-600"
      open={pending}
    >
      <summary className="cursor-pointer list-none text-xs font-medium text-stone-500">执行过程</summary>
      <div className="mt-2 space-y-2">
        {steps.map((step) => (
          <div key={step.invocationId} className="flex items-center justify-between gap-3 text-xs">
            <span className="min-w-0 truncate">{step.nodeName}</span>
            <span className="shrink-0 text-stone-500">{statusText(step.status)}</span>
          </div>
        ))}
      </div>
    </details>
  );
}

function ReasoningDetails({ content, pending = false }: { content: string; pending?: boolean }) {
  return (
    <details className="rounded-lg border border-stone-200 bg-stone-50 px-3 py-2 text-stone-600" open={pending}>
      <summary className="cursor-pointer list-none text-xs font-medium text-stone-500">
        {pending ? "思考中..." : "思考过程"}
      </summary>
      <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">{content}</div>
    </details>
  );
}

function AnswerContent({ content }: { content: string }) {
  return <p className="whitespace-pre-wrap">{content}</p>;
}

function ImageMessageContent({
  content,
  resources,
}: {
  content: string;
  resources: Array<{
    id: string;
    viewUrl: string;
    downloadUrl: string;
    fileName: string;
    width: number | null;
    height: number | null;
  }>;
}) {
  return (
    <div className="space-y-3">
      {resources.map((resource) => (
        <div key={resource.id} className="space-y-2">
          <img
            className="aspect-square w-full rounded-lg border border-stone-200 object-cover"
            src={resource.viewUrl}
            alt={content || resource.fileName}
            width={resource.width ?? 1024}
            height={resource.height ?? 1024}
          />
          <div className="flex items-start justify-between gap-3">
            <p className="min-w-0 whitespace-pre-wrap text-sm leading-6 text-stone-700">{content}</p>
            <a className="shrink-0 rounded-lg bg-stone-900 px-3 py-2 text-xs font-semibold text-white" href={resource.downloadUrl}>
              下载
            </a>
          </div>
        </div>
      ))}
    </div>
  );
}

export default function AgentsPage() {
  const router = useRouter();
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [selectedAgent, setSelectedAgent] = useState<AgentSummary | null>(null);
  const [selectedDomain, setSelectedDomain] = useState("全部");
  const [search, setSearch] = useState("");
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<UiChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [bootstrapping, setBootstrapping] = useState(true);
  const [switchingAgent, setSwitchingAgent] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState("");

  const domains = useMemo(() => ["全部", ...Array.from(new Set(agents.map((agent) => agent.domain)))], [agents]);
  const filteredAgents = useMemo(() => {
    const keyword = search.trim().toLowerCase();
    return agents.filter((agent) => {
      const domainMatched = selectedDomain === "全部" || agent.domain === selectedDomain;
      const keywordMatched =
        !keyword ||
        agent.displayName.toLowerCase().includes(keyword) ||
        agent.agentId.toLowerCase().includes(keyword) ||
        agent.summary.toLowerCase().includes(keyword) ||
        agent.tags.some((tag) => tag.toLowerCase().includes(keyword));
      return domainMatched && keywordMatched;
    });
  }, [agents, search, selectedDomain]);

  const canSubmit = input.trim().length > 0 && !!sessionId && !!selectedAgent && !streaming && !switchingAgent;

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        const list = await listAgents();
        setAgents(list);
        const requestedAgentId = new URLSearchParams(window.location.search).get("agentId");
        const initialAgent =
          list.find((agent) => agent.agentId === requestedAgentId) ??
          list.find((agent) => agent.agentId !== "standard-chat") ??
          list[0] ??
          null;
        if (initialAgent) {
          await openAgentSession(initialAgent, null);
        }
      })
      .catch(() => {
        savePostLoginRedirect("/agents");
        router.replace("/auth/login");
      })
      .finally(() => setBootstrapping(false));
  }, [router]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function openAgentSession(agent: AgentSummary, currentSessionId: string | null) {
    setSwitchingAgent(true);
    setError("");
    try {
      const detail = await createChatSession({
        currentSessionId,
        promptId: null,
        agentId: agent.agentId,
      });
      setSelectedAgent(agent);
      setSelectedDomain(agent.domain);
      setSessionId(detail.session.sessionId);
      setMessages(detail.messagePage.messages.map(toUiChatMessage));
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "切换 Agent 失败");
    } finally {
      setSwitchingAgent(false);
    }
  }

  async function handleSelectAgent(agent: AgentSummary) {
    if (streaming || switchingAgent || selectedAgent?.agentId === agent.agentId) return;
    await openAgentSession(agent, sessionId);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || !sessionId || !selectedAgent || streaming) return;

    const seed = Date.now();
    const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn(content, seed);

    setInput("");
    setError("");
    setStreaming(true);
    setMessages((current) => [...current, userMessage, reasoningMessage, assistantMessage]);

    try {
      await apiStream(
        "/api/chat/messages/stream",
        {
          method: "POST",
          body: JSON.stringify({
            message: content,
            sessionId,
            promptId: null,
            agentId: selectedAgent.agentId,
          }),
        },
        {
          onReasoning(chunk) {
            setMessages((current) => applyReasoningChunk(current, reasoningMessage.id, chunk));
          },
          onChunk(chunk) {
            setMessages((current) => applyAssistantChunk(current, assistantMessage.id, chunk));
          },
          onAgentStep(step) {
            setMessages((current) => applyAgentStep(current, assistantMessage.id, step));
          },
          onBlocked(message) {
            setMessages((current) => applyBlockedState(current, assistantMessage.id, message));
          },
          onImage(message) {
            setMessages((current) => applyImageMessage(current, assistantMessage.id, message));
          },
          onError(message) {
            setError(message);
          },
        },
      );
    } catch (streamError) {
      const message = streamError instanceof Error ? streamError.message : "发送失败";
      setError(message);
      setMessages((current) =>
        current.map((item) =>
          item.id === assistantMessage.id && !item.content
            ? { ...item, content: "暂时无法响应，请稍后重试。" }
            : item,
        ),
      );
      if (message === "Unauthorized") {
        savePostLoginRedirect("/agents");
        router.replace("/auth/login");
      }
    } finally {
      setStreaming(false);
    }
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
      <section className="mx-auto flex min-h-screen w-full max-w-md flex-col px-4 pb-28 pt-4">
        <header className="sticky top-0 z-20 -mx-4 border-b border-stone-200/70 bg-[#f7f4ea]/95 px-4 pb-3 pt-4 backdrop-blur">
          <div className="flex items-center justify-between gap-3">
            <div>
              <p className="text-xs font-medium text-amber-700">领域 Agent</p>
              <h1 className="mt-1 text-xl font-semibold">专业问答</h1>
            </div>
            <div className="flex shrink-0 items-center gap-3 text-sm font-medium text-amber-700">
              <Link href="/chat">普通聊天</Link>
              <Link href="/me/agents">管理</Link>
            </div>
          </div>

          <div className="mt-4 space-y-3">
            <input
              className="h-11 w-full rounded-lg border border-stone-200 bg-white px-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="搜索 Agent、领域或能力"
            />
            <div className="flex gap-2 overflow-x-auto pb-1">
              {domains.map((domain) => (
                <button
                  key={domain}
                  className={`shrink-0 rounded-lg border px-3 py-2 text-xs font-medium ${
                    domain === selectedDomain
                      ? "border-stone-900 bg-stone-900 text-white"
                      : "border-stone-200 bg-white text-stone-600"
                  }`}
                  type="button"
                  onClick={() => setSelectedDomain(domain)}
                >
                  {domain}
                </button>
              ))}
            </div>
          </div>
        </header>

        <div className="mt-4 flex gap-3 overflow-x-auto pb-2">
          {filteredAgents.map((agent) => (
            <button
              key={agent.agentId}
              className={`w-64 shrink-0 rounded-lg border p-3 text-left shadow-sm transition ${
                selectedAgent?.agentId === agent.agentId
                  ? "border-stone-900 bg-white"
                  : "border-stone-200 bg-white/80"
              }`}
              type="button"
              disabled={streaming || switchingAgent}
              onClick={() => handleSelectAgent(agent)}
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="text-xs text-amber-700">{agent.domain}</p>
                  <p className="mt-1 truncate text-sm font-semibold text-stone-900">{agent.displayName}</p>
                </div>
                <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-[11px] text-stone-500">
                  {agent.runtimeType}
                </span>
              </div>
              <p className="mt-2 line-clamp-2 text-xs leading-5 text-stone-500">{agent.summary}</p>
              <div className="mt-3 flex flex-wrap gap-1">
                {agent.tags.slice(0, 3).map((tag) => (
                  <span key={tag} className="rounded-md bg-amber-50 px-2 py-1 text-[11px] text-amber-700">
                    {tag}
                  </span>
                ))}
              </div>
            </button>
          ))}
        </div>

        {error ? <p className="mt-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        <div className="mt-4 flex-1 space-y-4">
          {bootstrapping || switchingAgent ? (
            <div className="rounded-lg border border-stone-200 bg-white/85 px-4 py-3 text-sm text-stone-500">
              {bootstrapping ? "正在加载 Agent..." : "正在切换 Agent..."}
            </div>
          ) : !selectedAgent ? (
            <div className="rounded-lg border border-stone-200 bg-white/85 px-4 py-3 text-sm text-stone-500">
              暂无可用领域 Agent。
            </div>
          ) : messages.length === 0 ? (
            <article className="flex justify-start">
              <div className="max-w-[85%] rounded-lg border border-stone-200 bg-white/95 px-4 py-3 text-sm leading-6 text-stone-700 shadow-sm">
                已选择 {selectedAgent.displayName}，可以开始提问。
              </div>
            </article>
          ) : null}

          {toRenderableTurns(messages).map((turn) => (
            <article key={turn.id} className={`flex ${turn.kind === "user" ? "justify-end" : "justify-start"}`}>
              <div
                className={[
                  "max-w-[85%] rounded-lg px-4 py-3 text-sm leading-6 shadow-sm",
                  turn.kind === "user"
                    ? "bg-stone-900 text-stone-50"
                    : turn.kind === "blocked"
                      ? "border border-amber-200 bg-amber-50 text-amber-900"
                      : "border border-stone-200 bg-white/95 text-stone-700",
                ].join(" ")}
              >
                {turn.kind === "user" ? (
                  turn.content
                ) : turn.kind === "image" ? (
                  <ImageMessageContent content={turn.content} resources={turn.resources} />
                ) : turn.kind === "blocked" ? (
                  <div className="space-y-3">
                    <AgentStepDetails steps={turn.agentSteps} />
                    {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                    <p className="whitespace-pre-wrap">{turn.blocked}</p>
                  </div>
                ) : turn.answer ? (
                  <div className="space-y-3">
                    <AgentStepDetails steps={turn.agentSteps} />
                    {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                    <AnswerContent content={turn.answer} />
                  </div>
                ) : turn.reasoning ? (
                  <div className="space-y-3">
                    <AgentStepDetails steps={turn.agentSteps} pending />
                    <ReasoningDetails content={turn.reasoning} pending />
                  </div>
                ) : (
                  <div className="space-y-3">
                    <AgentStepDetails steps={turn.agentSteps} pending />
                    {streaming ? "正在执行..." : ""}
                  </div>
                )}
              </div>
            </article>
          ))}
          <div ref={messageEndRef} />
        </div>

        <div className="fixed bottom-0 left-0 right-0 mx-auto w-full max-w-md bg-transparent px-4 pb-[calc(1rem+env(safe-area-inset-bottom))]">
          <form
            className="flex items-end gap-3 rounded-lg border border-stone-200 bg-[#f8f5ec]/95 p-3 shadow-[0_-8px_30px_rgba(58,45,28,0.12)] backdrop-blur"
            onSubmit={handleSubmit}
          >
            <textarea
              className="max-h-32 min-h-12 flex-1 resize-none rounded-lg border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder={selectedAgent ? `向 ${selectedAgent.displayName} 提问` : "选择 Agent 后提问"}
              rows={1}
            />
            <button
              className="h-12 shrink-0 rounded-lg bg-stone-900 px-5 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-400"
              type="submit"
              disabled={!canSubmit}
            >
              {streaming ? "生成中" : "发送"}
            </button>
          </form>
        </div>
      </section>
    </main>
  );
}

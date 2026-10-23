"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { AgentSummary, listAgents } from "@/lib/agents";
import { getCurrentUser } from "@/lib/auth";
import { agentChatHref } from "@/lib/chat-agent-mode";
import { savePostLoginRedirect } from "@/lib/session";

export default function AgentsPage() {
  const router = useRouter();
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [selectedDomain, setSelectedDomain] = useState("全部");
  const [search, setSearch] = useState("");
  const [bootstrapping, setBootstrapping] = useState(true);
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

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        try {
          const list = await listAgents();
          setAgents(list);
          const requestedAgentId = new URLSearchParams(window.location.search).get("agentId");
          const requestedAgent = list.find((agent) => agent.agentId === requestedAgentId);
          if (requestedAgent) {
            setSelectedDomain(requestedAgent.domain);
          }
        } catch (loadError) {
          setError(loadError instanceof Error ? loadError.message : "加载 Agent 失败");
        }
      })
      .catch(() => {
        savePostLoginRedirect("/agents");
        router.replace("/auth/login");
      })
      .finally(() => setBootstrapping(false));
  }, [router]);

  return (
    <main className="workspace-page text-stone-900">
      <section className="workspace-container flex flex-col">
        <header className="border-b border-stone-200 pb-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-xs font-medium text-amber-700">领域 Agent</p>
              <h1 className="mt-1 text-xl font-semibold">专业问答</h1>
            </div>
            <div className="flex shrink-0 items-center gap-3 text-sm font-medium text-amber-700">
              <Link href="/chat">普通聊天</Link>
              <Link href="/automations">自动化</Link>
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

        {error ? <p className="mt-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        <div className="workspace-card-grid mt-6">
          {bootstrapping ? (
            <div className="rounded-lg border border-stone-200 bg-white/85 px-4 py-3 text-sm text-stone-500">
              正在加载 Agent...
            </div>
          ) : filteredAgents.length === 0 ? (
            <div className="rounded-lg border border-stone-200 bg-white/85 px-4 py-3 text-sm text-stone-500">
              没有匹配的 Agent。
            </div>
          ) : (
            filteredAgents.map((agent) => (
              <article key={agent.agentId} className="workspace-agent-card rounded-xl border border-stone-200 bg-white p-5 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-xs text-amber-700">{agent.domain}</p>
                    <h2 className="mt-1 truncate text-base font-semibold">{agent.displayName}</h2>
                  </div>
                  <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-[11px] text-stone-500">
                    {agent.runtimeType}
                  </span>
                </div>
                <p className="mt-3 text-sm leading-6 text-stone-500">{agent.summary}</p>
                <div className="mt-3 flex flex-wrap gap-2">
                  {agent.tags.map((tag) => (
                    <span key={tag} className="rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-700">
                      {tag}
                    </span>
                  ))}
                </div>
                <div className="mt-4 grid grid-cols-2 gap-2">
                  <Link
                    className="rounded-lg border border-stone-200 px-3 py-2 text-center text-sm font-semibold text-stone-700"
                    href={`/me/agents/${encodeURIComponent(agent.agentId)}`}
                  >
                    查看编排
                  </Link>
                  <Link
                    className="rounded-lg bg-stone-900 px-3 py-2 text-center text-sm font-semibold text-white"
                    href={agentChatHref(agent.agentId)}
                  >
                    开始问答
                  </Link>
                </div>
              </article>
            ))
          )}
        </div>
      </section>
    </main>
  );
}

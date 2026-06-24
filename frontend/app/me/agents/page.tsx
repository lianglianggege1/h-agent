"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { AgentSummary, listAgents } from "@/lib/agents";
import { getCurrentUser } from "@/lib/auth";
import { agentChatHref } from "@/lib/chat-agent-mode";
import { savePostLoginRedirect } from "@/lib/session";

export default function AgentManagementPage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");

  const filteredAgents = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return agents;
    return agents.filter(
      (agent) =>
        agent.displayName.toLowerCase().includes(keyword) ||
        agent.agentId.toLowerCase().includes(keyword) ||
        agent.domain.toLowerCase().includes(keyword) ||
        agent.tags.some((tag) => tag.toLowerCase().includes(keyword)),
    );
  }, [agents, query]);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        try {
          setAgents(await listAgents());
        } catch (loadError) {
          setError(loadError instanceof Error ? loadError.message : "加载 Agent 失败");
        }
      })
      .catch(() => {
        savePostLoginRedirect("/me/agents");
        router.replace("/auth/login");
      });
  }, [router]);

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4">
        <header className="rounded-lg border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href="/me">
            返回我的
          </Link>
          <h1 className="mt-3 text-2xl font-semibold">领域 Agent 管理</h1>
          <p className="mt-2 text-sm text-stone-500">查看已注册 Agent、运行类型和编排拓扑。</p>
        </header>

        <input
          className="h-11 w-full rounded-lg border border-stone-200 bg-white px-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索 Agent"
        />

        {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        <div className="space-y-3">
          {filteredAgents.map((agent) => (
            <article key={agent.agentId} className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-xs text-amber-700">{agent.domain}</p>
                  <h2 className="mt-1 truncate text-base font-semibold">{agent.displayName}</h2>
                  <p className="mt-1 break-all text-xs text-stone-400">{agent.agentId}</p>
                </div>
                <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-[11px] text-stone-500">
                  {agent.enabled ? "启用" : "停用"}
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
                  className="rounded-lg bg-stone-900 px-3 py-2 text-center text-sm font-semibold text-white"
                  href={`/me/agents/${encodeURIComponent(agent.agentId)}`}
                >
                  查看编排
                </Link>
                <Link
                  className="rounded-lg border border-stone-200 px-3 py-2 text-center text-sm font-semibold text-stone-700"
                  href={agentChatHref(agent.agentId)}
                >
                  开始问答
                </Link>
              </div>
              <p className="mt-3 text-xs text-stone-400">运行类型：{agent.runtimeType}</p>
            </article>
          ))}
        </div>

        {filteredAgents.length === 0 ? (
          <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
            没有匹配的 Agent。
          </div>
        ) : null}
      </section>
    </main>
  );
}

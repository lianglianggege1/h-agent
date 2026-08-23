"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { AgentSummary, listAgents } from "@/lib/agents";
import { getCurrentUser } from "@/lib/auth";
import { agentChatHref } from "@/lib/chat-agent-mode";
import {
  SubagentCatalogView,
  SubagentDefinitionSummary,
  getSubagentDefinition,
  listSubagentCatalog,
} from "@/lib/subagent-catalog";
import { savePostLoginRedirect } from "@/lib/session";

type TabKey = "agents" | "system" | "mine";

const TABS: Array<{ key: TabKey; label: string }> = [
  { key: "agents", label: "顶级 Agent" },
  { key: "system", label: "系统 Subagents" },
  { key: "mine", label: "我的 Subagents" },
];

export default function AgentManagementPage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [tab, setTab] = useState<TabKey>("agents");
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [catalog, setCatalog] = useState<SubagentCatalogView | null>(null);
  const [query, setQuery] = useState("");
  const [error, setError] = useState("");
  const [catalogNotice, setCatalogNotice] = useState("");

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

  const filterSubagents = useCallback(
    (items: SubagentDefinitionSummary[]) => {
      const keyword = query.trim().toLowerCase();
      if (!keyword) return items;
      return items.filter(
        (item) =>
          item.displayName.toLowerCase().includes(keyword) ||
          item.agentId.toLowerCase().includes(keyword) ||
          item.description.toLowerCase().includes(keyword),
      );
    },
    [query],
  );

  const loadCatalog = useCallback(async () => {
    try {
      setCatalog(await listSubagentCatalog());
      setCatalogNotice("");
    } catch (loadError) {
      setCatalog(null);
      setCatalogNotice(loadError instanceof Error ? loadError.message : "加载 Subagent 目录失败");
    }
  }, []);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        try {
          setAgents(await listAgents());
        } catch (loadError) {
          setError(loadError instanceof Error ? loadError.message : "加载 Agent 失败");
        }
        await loadCatalog();
      })
      .catch(() => {
        savePostLoginRedirect("/me/agents");
        router.replace("/auth/login");
      });
  }, [loadCatalog, router]);

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
          <p className="mt-2 text-sm text-stone-500">查看已注册 Agent、运行类型和编排拓扑，管理 Subagent 定义。</p>
        </header>

        <div className="flex gap-2 overflow-x-auto pb-1">
          {TABS.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`shrink-0 rounded-full border px-4 py-2 text-sm shadow-sm ${
                tab === item.key
                  ? "border-stone-900 bg-stone-900 text-white"
                  : "border-stone-200 bg-white/90 text-stone-600"
              }`}
              onClick={() => setTab(item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>

        <input
          className="h-11 w-full rounded-lg border border-stone-200 bg-white px-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索"
        />

        {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        {tab === "agents" ? (
          <TopLevelAgentList agents={filteredAgents} />
        ) : null}

        {tab === "system" ? (
          catalog ? (
            <SystemSubagentList items={filterSubagents(catalog.system)} />
          ) : (
            <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
              {catalogNotice || "Subagent 目录不可用。"}
            </div>
          )
        ) : null}

        {tab === "mine" ? (
          catalog ? (
            <MineSubagentList
              items={filterSubagents(catalog.mine)}
              limits={catalog.limits}
            />
          ) : (
            <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
              {catalogNotice || "Subagent 目录不可用。"}
            </div>
          )
        ) : null}
      </section>
    </main>
  );
}

function TopLevelAgentList({ agents }: { agents: AgentSummary[] }) {
  if (agents.length === 0) {
    return (
      <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
        没有匹配的 Agent。
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {agents.map((agent) => (
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
  );
}

function SystemSubagentList({ items }: { items: SubagentDefinitionSummary[] }) {
  const [detailFor, setDetailFor] = useState<string | null>(null);
  const [detailMarkdown, setDetailMarkdown] = useState<string | null>(null);
  const [detailError, setDetailError] = useState("");

  async function openDetail(agentId: string) {
    setDetailFor(agentId);
    setDetailMarkdown(null);
    setDetailError("");
    try {
      const detail = await getSubagentDefinition(agentId);
      setDetailMarkdown(detail.currentMarkdown ?? detail.draftMarkdown ?? "");
    } catch (loadError) {
      setDetailError(loadError instanceof Error ? loadError.message : "加载详情失败");
    }
  }

  if (items.length === 0) {
    return (
      <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
        没有匹配的系统 Subagent。
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {items.map((item) => (
        <article key={item.agentId} className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="truncate text-base font-semibold">{item.displayName}</h2>
              <p className="mt-1 break-all text-xs text-stone-400">{item.agentId}</p>
            </div>
            <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-[11px] text-stone-500">
              内置 · v{item.currentVersion ?? "-"}
            </span>
          </div>
          <p className="mt-3 text-sm leading-6 text-stone-500">{item.description}</p>
          {detailFor !== item.agentId ? (
            <button
              type="button"
              className="mt-4 w-full rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700"
              onClick={() => void openDetail(item.agentId)}
            >
              查看 Markdown
            </button>
          ) : (
            <div className="mt-4 space-y-2">
              {detailError ? (
                <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{detailError}</p>
              ) : null}
              {detailMarkdown !== null ? (
                <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 font-mono text-xs leading-5 text-stone-700">
                  {detailMarkdown}
                </pre>
              ) : (
                <p className="text-sm text-stone-400">加载中…</p>
              )}
              <button
                type="button"
                className="w-full rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700"
                onClick={() => setDetailFor(null)}
              >
                收起
              </button>
            </div>
          )}
        </article>
      ))}
    </div>
  );
}

function MineSubagentList({
  items,
  limits,
}: {
  items: SubagentDefinitionSummary[];
  limits: SubagentCatalogView["limits"];
}) {
  if (items.length === 0) {
    return (
      <div className="space-y-3">
        <Link
          className="block rounded-lg bg-stone-900 px-3 py-3 text-center text-sm font-semibold text-white"
          href="/me/agents/subagents/new"
        >
          新建 Subagent
        </Link>
        <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
          还没有自定义 Subagent。
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <Link
        className="block rounded-lg bg-stone-900 px-3 py-3 text-center text-sm font-semibold text-white"
        href="/me/agents/subagents/new"
      >
        新建 Subagent
      </Link>
      {limits ? (
        <p className="rounded-lg border border-stone-200 bg-white/90 px-4 py-2 text-xs text-stone-500">
          定义 {limits.usedDefinitions}/{limits.maxDefinitions} · 启用 {limits.usedEnabled}/{limits.maxEnabled}
        </p>
      ) : null}
      {items.map((item) => (
        <Link
          key={item.agentId}
          href={`/me/agents/subagents/${encodeURIComponent(item.agentId)}`}
          className="block rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm transition hover:border-amber-400"
        >
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h2 className="truncate text-base font-semibold">{item.displayName}</h2>
              <p className="mt-1 break-all text-xs text-stone-400">{item.agentId}</p>
            </div>
            <span
              className={`shrink-0 rounded-md px-2 py-1 text-[11px] ${
                item.deleted
                  ? "bg-red-50 text-red-500"
                  : item.enabled
                    ? "bg-emerald-50 text-emerald-700"
                    : "bg-stone-100 text-stone-500"
              }`}
            >
              {item.deleted ? "已删除" : item.enabled ? "已启用" : "未启用"}
            </span>
          </div>
          <p className="mt-3 text-sm leading-6 text-stone-500">{item.description}</p>
          <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
            <span className="rounded-md bg-stone-50 px-2 py-1 text-stone-500">
              草稿 r{item.draftRevision ?? "-"}
            </span>
            <span
              className={`rounded-md px-2 py-1 ${
                item.draftValid === false ? "bg-red-50 text-red-500" : "bg-stone-50 text-stone-500"
              }`}
            >
              {item.draftValid === false ? "草稿有错误" : "草稿有效"}
            </span>
            <span className="rounded-md bg-stone-50 px-2 py-1 text-stone-500">
              当前 v{item.currentVersion ?? "未发布"}
            </span>
          </div>
          {item.updatedAt ? (
            <p className="mt-3 text-xs text-stone-400">更新于 {new Date(item.updatedAt).toLocaleString()}</p>
          ) : null}
        </Link>
      ))}
    </div>
  );
}

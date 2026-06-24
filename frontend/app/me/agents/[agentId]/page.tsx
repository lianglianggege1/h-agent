"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { AgentTopology, AgentTopologyNode, getAgentTopology } from "@/lib/agents";
import { collectTopologyLegend, topologyLabel, topologyTone } from "@/lib/agent-ui";
import { getCurrentUser } from "@/lib/auth";
import { agentChatHref } from "@/lib/chat-agent-mode";
import { savePostLoginRedirect } from "@/lib/session";

const toneClasses: Record<string, { badge: string; border: string; dot: string }> = {
  ai: {
    badge: "bg-[#2e8555] text-white",
    border: "border-l-[#2e8555]",
    dot: "bg-[#2e8555]",
  },
  nonai: {
    badge: "bg-[#6b7280] text-white",
    border: "border-l-[#6b7280]",
    dot: "bg-[#6b7280]",
  },
  human: {
    badge: "bg-[#d97706] text-white",
    border: "border-l-[#d97706]",
    dot: "bg-[#d97706]",
  },
  seq: {
    badge: "bg-[#0891b2] text-white",
    border: "border-l-[#0891b2]",
    dot: "bg-[#0891b2]",
  },
  par: {
    badge: "bg-[#3b82f6] text-white",
    border: "border-l-[#3b82f6]",
    dot: "bg-[#3b82f6]",
  },
  loop: {
    badge: "bg-[#7c3aed] text-white",
    border: "border-l-[#7c3aed]",
    dot: "bg-[#7c3aed]",
  },
  rtr: {
    badge: "bg-[#dc2626] text-white",
    border: "border-l-[#dc2626]",
    dot: "bg-[#dc2626]",
  },
  star: {
    badge: "bg-[#ca8a04] text-white",
    border: "border-l-[#ca8a04]",
    dot: "bg-[#ca8a04]",
  },
};

function toneClass(tone: string) {
  return toneClasses[tone] ?? toneClasses.nonai;
}

function Detail({ label, value }: { label: string; value: string | null | undefined }) {
  if (!value) return null;

  return (
    <div className="mt-1 flex gap-1.5 text-[11px] leading-5">
      <span className="shrink-0 font-semibold text-slate-400">{label}</span>
      <span className="min-w-0 truncate text-slate-600" title={value}>
        {value}
      </span>
    </div>
  );
}

function LoopInfo({ node }: { node: AgentTopologyNode }) {
  if (!node.loop) return null;

  return (
    <div className="mt-2 flex flex-wrap gap-1">
      <span className="rounded-[3px] border border-violet-200 bg-violet-50 px-1.5 text-[10px] leading-5 text-violet-700">
        max {node.loop.maxIterations ?? "-"}
      </span>
      {node.loop.exitCondition ? (
        <span
          className="max-w-40 truncate rounded-[3px] border border-violet-200 bg-violet-50 px-1.5 text-[10px] leading-5 text-violet-700"
          title={node.loop.exitCondition}
        >
          exit: {node.loop.exitCondition}
        </span>
      ) : null}
      <span className="rounded-[3px] border border-violet-200 bg-violet-50 px-1.5 text-[10px] leading-5 text-violet-700">
        {node.loop.testExitAtLoopEnd ? "test at end" : "test at start"}
      </span>
    </div>
  );
}

function TopologyNodeView({ node }: { node: AgentTopologyNode }) {
  const hasChildren = node.children.length > 0;
  const tone = toneClass(topologyTone(node.topology));
  const type = node.type && node.type !== node.name ? node.type : null;

  return (
    <li className="relative flex flex-col items-center px-2 pt-6 before:absolute before:right-1/2 before:top-0 before:h-6 before:w-1/2 before:border-t before:border-slate-300 after:absolute after:left-1/2 after:top-0 after:h-6 after:w-1/2 after:border-l after:border-t after:border-slate-300 first:before:border-t-0 last:after:border-t-0 only:before:hidden only:after:border-t-0">
      {node.condition ? (
        <div
          className="mb-1.5 max-w-52 truncate rounded-[3px] border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] leading-4 text-amber-700"
          title={node.condition}
        >
          when: {node.condition}
        </div>
      ) : null}

      <div
        className={`w-56 rounded-md border border-slate-200 border-l-4 ${tone.border} bg-white px-3 py-2.5 text-left shadow-[0_1px_2px_rgba(15,23,42,0.06)]`}
      >
        <div className="flex min-w-0 items-center gap-1.5">
          <span className={`shrink-0 rounded-[3px] px-1.5 py-0.5 text-[10px] font-bold ${tone.badge}`}>
            {topologyLabel(node.topology)}
          </span>
          <span className="min-w-0 truncate text-sm font-semibold text-slate-900" title={node.name}>
            {node.name}
          </span>
          {node.async ? (
            <span className="shrink-0 rounded-[3px] border border-amber-200 bg-amber-50 px-1 text-[9px] font-medium text-amber-700">
              async
            </span>
          ) : null}
        </div>

        <div className="mt-1.5">
          <Detail label="Type" value={type} />
          <Detail label="Desc" value={node.description} />
          <Detail label="Returns" value={node.returnType} />
          <LoopInfo node={node} />
        </div>
      </div>

      {hasChildren ? (
        <ul className="relative flex justify-center pt-6 before:absolute before:left-1/2 before:top-0 before:h-6 before:border-l before:border-slate-300">
          {node.children.map((child) => (
            <TopologyNodeView key={child.nodeId} node={child} />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

export default function AgentTopologyPage() {
  const params = useParams<{ agentId: string }>();
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [topology, setTopology] = useState<AgentTopology | null>(null);
  const [error, setError] = useState("");

  const agentId = decodeURIComponent(params.agentId);
  const legend = useMemo(() => (topology ? collectTopologyLegend(topology.root) : []), [topology]);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        setTopology(await getAgentTopology(agentId));
      })
      .catch((loadError) => {
        if (loadError instanceof Error && loadError.message !== "Unauthorized") {
          setError(loadError.message);
          setAuthenticated(true);
          return;
        }
        savePostLoginRedirect(`/me/agents/${encodeURIComponent(agentId)}`);
        router.replace("/auth/login");
      });
  }, [agentId, router]);

  if (!authenticated) {
    return <main className="min-h-screen bg-[#f7f8fa]" />;
  }

  return (
    <main className="min-h-screen bg-[#f7f8fa] px-4 py-5 text-slate-900 sm:px-6">
      <section className="mx-auto w-full max-w-6xl space-y-4">
        <header className="rounded-lg border border-slate-200 bg-white px-4 py-4 shadow-sm sm:px-5">
          <Link className="text-sm font-medium text-emerald-700" href="/me/agents">
            返回 Agent 管理
          </Link>
          <div className="mt-3 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div className="min-w-0">
              <h1 className="truncate text-2xl font-semibold">{topology?.agent.displayName ?? "Agent 编排"}</h1>
              {topology?.agent.summary ? (
                <p className="mt-1 max-w-3xl text-sm leading-6 text-slate-500">{topology.agent.summary}</p>
              ) : null}
            </div>
            {topology ? (
              <Link
                className="inline-flex h-9 items-center justify-center rounded-md bg-emerald-700 px-3 text-sm font-medium text-white transition hover:bg-emerald-800"
                href={agentChatHref(topology.agent.agentId)}
              >
                开始问答
              </Link>
            ) : null}
          </div>
        </header>

        {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        {topology ? (
          <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
            <div className="flex flex-col gap-3 border-b border-slate-200 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
              <h2 className="text-lg font-semibold">System Topology</h2>
              <div className="flex flex-wrap gap-x-4 gap-y-2">
                {legend.map((item) => (
                  <div key={item.topology} className="flex items-center gap-1.5 text-xs text-slate-600">
                    <span className={`h-2.5 w-2.5 rounded-[3px] ${toneClass(item.tone).dot}`} />
                    <span>{item.label}</span>
                  </div>
                ))}
              </div>
            </div>

            <div className="overflow-x-auto px-2 pb-5 pt-2 sm:px-4">
              <div className="inline-block min-w-full text-center">
                <ul className="inline-flex justify-center">
                  <TopologyNodeView node={topology.root} />
                </ul>
              </div>
            </div>
          </section>
        ) : !error ? (
          <div className="rounded-lg border border-slate-200 bg-white px-4 py-3 text-sm text-slate-500">
            正在加载拓扑...
          </div>
        ) : null}
      </section>
    </main>
  );
}

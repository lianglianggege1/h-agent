"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AgentTopology, AgentTopologyNode, getAgentTopology } from "@/lib/agents";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";

function topologyClassName(topology: string | null) {
  if (topology === "ROUTER") return "border-sky-200 bg-sky-50 text-sky-700";
  if (topology === "LOOP") return "border-amber-200 bg-amber-50 text-amber-700";
  if (topology === "PARALLEL") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (topology === "STAR") return "border-fuchsia-200 bg-fuchsia-50 text-fuchsia-700";
  if (topology === "SEQUENCE") return "border-stone-200 bg-stone-50 text-stone-700";
  return "border-indigo-200 bg-indigo-50 text-indigo-700";
}

function TopologyNodeView({
  node,
  onSelect,
}: {
  node: AgentTopologyNode;
  onSelect: (node: AgentTopologyNode) => void;
}) {
  const hasChildren = node.children.length > 0;
  const isParallelLike = node.topology === "PARALLEL" || node.topology === "STAR";

  return (
    <li className="relative flex flex-col items-center">
      {node.condition ? (
        <div className="mb-2 max-w-48 rounded-md border border-sky-200 bg-white px-2 py-1 text-center text-[11px] text-sky-700">
          {node.condition}
        </div>
      ) : null}
      <button
        className="w-52 rounded-lg border border-stone-200 bg-white p-3 text-left shadow-sm transition hover:border-amber-400"
        type="button"
        onClick={() => onSelect(node)}
      >
        <div className="flex items-center justify-between gap-2">
          <span className={`rounded-md border px-2 py-1 text-[11px] font-semibold ${topologyClassName(node.topology)}`}>
            {node.topology || "AGENT"}
          </span>
          {node.async ? <span className="rounded-md bg-emerald-50 px-2 py-1 text-[11px] text-emerald-700">async</span> : null}
        </div>
        <strong className="mt-2 block text-sm text-stone-900">{node.name}</strong>
        <span className="mt-1 block truncate text-xs text-stone-400">{node.nodeId}</span>
      </button>
      {hasChildren ? (
        <>
          <div className="h-5 w-px bg-stone-300" />
          <ul className={`flex gap-4 ${isParallelLike ? "items-start" : "items-start"}`}>
            {node.children.map((child) => (
              <TopologyNodeView key={child.nodeId} node={child} onSelect={onSelect} />
            ))}
          </ul>
        </>
      ) : null}
    </li>
  );
}

function NodeDrawer({ node, onClose }: { node: AgentTopologyNode | null; onClose: () => void }) {
  if (!node) return null;

  return (
    <div className="fixed inset-x-0 bottom-0 z-40 mx-auto w-full max-w-md px-3 pb-3">
      <div className="rounded-lg border border-stone-200 bg-white p-4 shadow-[0_-16px_60px_rgba(31,27,22,0.18)]">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs text-amber-700">{node.topology}</p>
            <h2 className="mt-1 text-lg font-semibold">{node.name}</h2>
            <p className="mt-1 break-all text-xs text-stone-400">{node.nodeId}</p>
          </div>
          <button className="rounded-lg border border-stone-200 px-3 py-2 text-sm text-stone-600" type="button" onClick={onClose}>
            关闭
          </button>
        </div>
        {node.description ? <p className="mt-3 text-sm leading-6 text-stone-500">{node.description}</p> : null}
        <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
          <div className="rounded-lg bg-stone-50 p-3">
            <dt className="text-xs text-stone-400">类型</dt>
            <dd className="mt-1 break-all text-stone-700">{node.type || "-"}</dd>
          </div>
          <div className="rounded-lg bg-stone-50 p-3">
            <dt className="text-xs text-stone-400">输出</dt>
            <dd className="mt-1 break-all text-stone-700">{node.outputKey || "-"}</dd>
          </div>
          <div className="rounded-lg bg-stone-50 p-3">
            <dt className="text-xs text-stone-400">返回类型</dt>
            <dd className="mt-1 break-all text-stone-700">{node.returnType || "-"}</dd>
          </div>
          <div className="rounded-lg bg-stone-50 p-3">
            <dt className="text-xs text-stone-400">输入</dt>
            <dd className="mt-1 break-all text-stone-700">{node.inputKeys.length ? node.inputKeys.join(", ") : "-"}</dd>
          </div>
        </dl>
        {node.loop ? (
          <div className="mt-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
            <p>最大循环：{node.loop.maxIterations ?? "-"}</p>
            <p className="mt-1">退出条件：{node.loop.exitCondition || "-"}</p>
            <p className="mt-1">循环末尾检测：{node.loop.testExitAtLoopEnd ? "是" : "否"}</p>
          </div>
        ) : null}
      </div>
    </div>
  );
}

export default function AgentTopologyPage() {
  const params = useParams<{ agentId: string }>();
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [topology, setTopology] = useState<AgentTopology | null>(null);
  const [selectedNode, setSelectedNode] = useState<AgentTopologyNode | null>(null);
  const [error, setError] = useState("");

  const agentId = decodeURIComponent(params.agentId);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        const detail = await getAgentTopology(agentId);
        setTopology(detail);
        setSelectedNode(detail.root);
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
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4 pb-36">
        <header className="rounded-lg border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href="/me/agents">
            返回 Agent 管理
          </Link>
          <h1 className="mt-3 text-2xl font-semibold">{topology?.agent.displayName ?? "Agent 编排"}</h1>
          {topology ? (
            <>
              <p className="mt-2 text-sm leading-6 text-stone-500">{topology.agent.summary}</p>
              <div className="mt-3 flex flex-wrap gap-2">
                <span className="rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{topology.agent.domain}</span>
                <span className="rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{topology.agent.runtimeType}</span>
                {topology.agent.tags.map((tag) => (
                  <span key={tag} className="rounded-md bg-amber-50 px-2 py-1 text-xs text-amber-700">
                    {tag}
                  </span>
                ))}
              </div>
            </>
          ) : null}
        </header>

        {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        {topology ? (
          <>
            <div className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <h2 className="text-base font-semibold">编排拓扑</h2>
                <Link className="text-sm font-medium text-amber-700" href={`/agents?agentId=${encodeURIComponent(topology.agent.agentId)}`}>
                  开始问答
                </Link>
              </div>
              <div className="mt-4 overflow-x-auto pb-3">
                <ul className="inline-flex min-w-max justify-center px-2">
                  <TopologyNodeView node={topology.root} onSelect={setSelectedNode} />
                </ul>
              </div>
            </div>

            <div className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <h2 className="text-base font-semibold">状态 Key</h2>
              <div className="mt-3 flex flex-wrap gap-2">
                {topology.stateKeys.map((item) => (
                  <span
                    key={item.key}
                    className="rounded-md border border-stone-200 bg-white px-2 py-1 text-xs text-stone-600"
                    style={{ borderColor: item.color }}
                  >
                    {item.key}
                    <span className="ml-1 text-stone-400">{item.type}</span>
                  </span>
                ))}
                {topology.stateKeys.length === 0 ? <span className="text-sm text-stone-500">暂无状态 key</span> : null}
              </div>
            </div>
          </>
        ) : !error ? (
          <div className="rounded-lg border border-stone-200 bg-white/90 px-4 py-3 text-sm text-stone-500">
            正在加载拓扑...
          </div>
        ) : null}
      </section>

      <NodeDrawer node={selectedNode} onClose={() => setSelectedNode(null)} />
    </main>
  );
}

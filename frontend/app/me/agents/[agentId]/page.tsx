"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { type AgentTopology, getAgentTopology } from "@/lib/agents";
import { flattenTopology, stateKeyUsage, type TopologyEntry } from "@/lib/agent-topology";
import { AgentNodeDetails } from "@/components/agent-node-details";
import { AgentNodePopover } from "@/components/agent-node-popover";
import { AgentTopologyGraph } from "@/components/agent-topology-graph";
import { getCurrentUser } from "@/lib/auth";
import { ApiError } from "@/lib/http";
import { agentChatHref } from "@/lib/chat-agent-mode";
import { savePostLoginRedirect } from "@/lib/session";
import styles from "./topology.module.css";

function EntryLinks({ entries, onSelect, empty }: { entries: TopologyEntry[]; onSelect: (path: string) => void; empty: string }) {
  return entries.length ? <div className={styles.links}>{entries.map(entry => <button type="button" key={entry.path} onClick={() => onSelect(entry.path)}>{entry.node.name || entry.node.nodeId}</button>)}</div> : <span className={styles.muted}>{empty}</span>;
}

export default function AgentTopologyPage() {
  const { agentId } = useParams<{ agentId: string }>();
  const router = useRouter();
  const [topology, setTopology] = useState<AgentTopology | null>(null);
  const [selectedPath, setSelectedPath] = useState("0");
  const [popup, setPopup] = useState<{ path: string; anchor: HTMLElement } | null>(null);
  const closePopup = useCallback(() => setPopup(null), []);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [refresh, setRefresh] = useState(0);
  const [fetchedAt, setFetchedAt] = useState("");
  const entries = useMemo(() => topology ? flattenTopology(topology.root) : [], [topology]);
  const selected = entries.find(entry => entry.path === selectedPath) ?? entries[0];
  const current = topology?.agent.agentId === agentId ? topology : null;

  useEffect(() => {
    const controller = new AbortController();
    let active = true;
    void getCurrentUser().then(() => getAgentTopology(agentId, controller.signal)).then(data => {
      if (!active) return;
      setTopology(data);
      setError("");
      setFetchedAt(new Date().toLocaleTimeString("zh-CN", { hour12: false }));
    }).catch(failure => {
      if (!active) return;
      if ((failure instanceof ApiError && failure.code === 40100) || (failure instanceof Error && failure.message === "Unauthorized")) {
        savePostLoginRedirect(`/me/agents/${encodeURIComponent(agentId)}`);
        router.replace("/auth/login");
      } else setError(failure instanceof Error ? failure.message : "编排信息加载失败");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; controller.abort(); };
  }, [agentId, router, refresh]);

  function reload() { setLoading(true); setError(""); setRefresh(value => value + 1); }
  function select(path: string) {
    setSelectedPath(path);
    document.getElementById("node-detail")?.scrollIntoView({ block: "nearest", behavior: "instant" });
  }

  const popupEntry = popup ? entries.find(entry => entry.path === popup.path) : undefined;

  return <main className={`workspace-page resource-page ${styles.page}`}>
    <section className="workspace-container space-y-5">
      <header>
        <Link href="/me/agents" className={styles.back}>← 智能体管理</Link>
        <div className={styles.heading}>
          <div><h1>{current?.agent.displayName ?? "智能体编排"}</h1><p className={styles.muted}>{current?.agent.summary || "查看智能体的执行结构与数据流。"}</p></div>
          <div className={styles.actions}><button type="button" className="agui-button" disabled={loading} onClick={reload}>{loading ? "加载中…" : "刷新"}</button>{current?.agent.enabled ? <Link className="agui-button agui-button-primary" href={agentChatHref(current.agent.agentId)}>开始问答</Link> : null}</div>
        </div>
        {current ? <div className={styles.metadata}><code>{current.agent.agentId}</code><span>{current.agent.domain}</span><span>{current.agent.runtimeType}</span><span>{current.agent.enabled ? "已启用" : "未启用"}</span>{current.agent.tags.map(tag => <span key={tag}>{tag}</span>)}</div> : null}
      </header>
      {error ? <div role="alert" className={styles.error}><p>{error}</p><button type="button" className="agui-button" onClick={reload} disabled={loading}>重新加载</button></div> : null}
      {!current && loading ? <p role="status" className={styles.muted}>正在获取编排信息…</p> : null}
      {current && selected ? <>
        <div className={styles.overview}><span>{entries.length} 个节点</span><span>{entries.filter(entry => entry.node.children.length > 0).length} 个编排节点</span><span>{current.stateKeys.length} 个状态键</span><span className={styles.fetched}>获取时间 {fetchedAt}</span></div>
        <div className={styles.layout} aria-busy={loading}>
          <section className={styles.panel} aria-label="编排结构"><AgentTopologyGraph root={current.root} selected={selected?.path ?? "0"} onSelect={setSelectedPath} openDetailPath={popup?.path ?? null} onDetails={(path, anchor) => { setSelectedPath(path); setPopup({ path, anchor }); }} /></section>
          <section className={styles.panel} id="node-detail" aria-label="节点详情">
            <AgentNodeDetails entry={selected} entries={entries} stateKeys={current.stateKeys} onSelect={select} />
          </section>
        </div>
        <section className={styles.panel}><div className={styles.panelHeader}><div><h2>状态与数据流</h2><p className={styles.muted}>由后端声明的输入与输出键整理。条件分支不一定在每次运行时执行。</p></div></div><div className={styles.tableWrap}><table><thead><tr><th>状态键</th><th>类型</th><th>输出节点</th><th>输入节点</th></tr></thead><tbody>{current.stateKeys.map(key => { const usage = stateKeyUsage(entries, key.key); return <tr key={key.key}><td><code>{key.key}</code></td><td><code>{key.type || "未提供"}</code></td><td><EntryLinks entries={usage.producers} onSelect={select} empty="外部输入或上下文" /></td><td><EntryLinks entries={usage.consumers} onSelect={select} empty="无声明的读取节点" /></td></tr>; })}</tbody></table>{!current.stateKeys.length ? <p className={styles.empty}>未声明共享状态键</p> : null}</div></section>
        <details className={`${styles.panel} ${styles.raw}`}><summary>接口原始数据 <span className={styles.muted}>GET /api/agents/{encodeURIComponent(agentId)}/topology</span></summary><pre>{JSON.stringify(current, null, 2)}</pre></details>
      </> : null}
    </section>
    {current && popup && popupEntry && popup.anchor.isConnected ? <AgentNodePopover anchor={popup.anchor} entry={popupEntry} entries={entries} stateKeys={current.stateKeys}
      onClose={closePopup} onSelect={path => { setSelectedPath(path); setPopup(previous => previous ? { ...previous, path } : null); }} /> : null}
  </main>;
}

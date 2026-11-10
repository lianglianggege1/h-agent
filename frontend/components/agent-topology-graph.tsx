"use client";

import { useEffect, useRef, useState } from "react";
import type { AgentTopologyNode } from "@/lib/agents";
import styles from "./agent-topology-graph.module.css";

const labels: Record<string, string> = {
  AI_AGENT: "AI 节点", NON_AI_AGENT: "逻辑节点", HUMAN_IN_THE_LOOP: "人工确认",
  SEQUENCE: "顺序执行", PARALLEL: "并行执行", LOOP: "循环", ROUTER: "条件路由", STAR: "主管调度",
};

function GraphNode({ node, path = "0", selected, onSelect, onDetails, openDetailPath }: {
  node: AgentTopologyNode; path?: string; selected: string; onSelect: (path: string) => void;
  onDetails: (path: string, anchor: HTMLElement) => void; openDetailPath: string | null;
}) {
  const type = node.decisionRouter ? "决策路由" : labels[node.topology] ?? node.topology ?? "节点";
  const position = path === "0" ? "根节点" : path.slice(2).split(".").map(Number).map(n => n + 1).join(".");
  return <li className={styles.branch}>
    <div className={`${styles.card} ${selected === path ? styles.selected : ""}`}>
      <button type="button" className={styles.cardMain} aria-current={selected === path ? "true" : undefined} onClick={() => onSelect(path)}>
      <span className={styles.cardHeader}><span className={styles.badge}>{type}</span><span className={styles.position}>{position}</span></span>
      <strong title={node.name || node.nodeId}>{node.name || node.nodeId}</strong>
      <span className={styles.description} title={node.description || undefined}>{node.description || (node.children.length ? `${node.children.length} 个子节点` : node.type || "未提供说明")}</span>
      <span className={styles.io}><span>输入</span><code title={node.inputKeys.join(", ")}>{node.inputKeys.join(", ") || "未声明"}</code></span>
      <span className={styles.io}><span>输出</span><code title={node.outputKey || undefined}>{node.outputKey || "未声明"}</code></span>
      </button>
      <div className={styles.cardFooter}><span className={styles.execution} title={node.condition || undefined}>
        {node.condition || (node.decisionRouter ? `${node.decisionRouter.selectionMode === "MULTIPLE" ? "独立多选" : "单选"}${node.decisionRouter.activationThreshold == null ? "" : ` · 阈值 ${node.decisionRouter.activationThreshold}`}` : node.loop ? `最多 ${node.loop.maxIterations ?? "—"} 轮` : `${node.async ? "异步" : "同步"}${node.children.length ? ` · ${node.children.length} 个子节点` : ""}`)}
      </span>
      <button type="button" className={styles.detailsButton} aria-label={`查看 ${node.name || node.nodeId} 的详情`} aria-haspopup="dialog" aria-expanded={openDetailPath === path} aria-controls={openDetailPath === path ? "agent-node-popover" : undefined} onClick={event => onDetails(path, event.currentTarget)}>详情 <span aria-hidden="true">↗</span></button></div>
    </div>
    {node.children.length ? <ul className={styles.children}>{node.children.map((child, index) => <GraphNode key={`${path}.${index}`} node={child} path={`${path}.${index}`} selected={selected} onSelect={onSelect} onDetails={onDetails} openDetailPath={openDetailPath} />)}</ul> : null}
  </li>;
}

const clampZoom = (value: number) => Math.max(.25, Math.min(1.6, value));

export function AgentTopologyGraph({ root, selected, onSelect, onDetails, openDetailPath }: {
  root: AgentTopologyNode; selected: string; onSelect: (path: string) => void;
  onDetails: (path: string, anchor: HTMLElement) => void; openDetailPath: string | null;
}) {
  const viewport = useRef<HTMLDivElement>(null);
  const tree = useRef<HTMLUListElement>(null);
  const [zoom, setZoom] = useState(1);
  const [size, setSize] = useState({ width: 0, height: 0, available: 0 });

  useEffect(() => {
    const measure = () => {
      if (!tree.current || !viewport.current) return;
      const next = { width: tree.current.offsetWidth, height: tree.current.offsetHeight, available: viewport.current.clientWidth };
      setSize(previous => previous.width === next.width && previous.height === next.height && previous.available === next.available ? previous : next);
    };
    const observer = new ResizeObserver(measure);
    if (tree.current) observer.observe(tree.current);
    if (viewport.current) observer.observe(viewport.current);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (viewport.current && size.width) {
      viewport.current.scrollLeft = (viewport.current.scrollWidth - viewport.current.clientWidth) / 2;
    }
  }, [size.width, size.available]);

  function fit() {
    if (!viewport.current || !tree.current) return;
    setZoom(clampZoom(Math.min(1, (viewport.current.clientWidth - 48) / tree.current.offsetWidth)));
    viewport.current.scrollTo({ left: 0, top: 0 });
  }

  return <>
    <div className={styles.toolbar}>
      <div><h2>编排结构</h2><p>点击卡片上的“详情”就地查看完整信息。宽图可横向滚动。</p></div>
      <div className={styles.controls} role="group" aria-label="图形缩放">
        <button type="button" aria-label="缩小编排图" disabled={zoom <= .25} onClick={() => setZoom(value => clampZoom(value - .1))}>−</button>
        <button type="button" aria-label="重置编排图缩放" onClick={() => setZoom(1)}>{Math.round(zoom * 100)}%</button>
        <button type="button" aria-label="放大编排图" disabled={zoom >= 1.6} onClick={() => setZoom(value => clampZoom(value + .1))}>＋</button>
        <button type="button" onClick={fit}>适应宽度</button>
      </div>
    </div>
    <div ref={viewport} className={styles.viewport} tabIndex={0} role="region" aria-label="编排图画布，可横向滚动">
      <nav aria-label="节点导航" className={styles.surface} style={{ width: size.width ? Math.max(size.available, size.width * zoom + 48) : "100%", height: size.height ? size.height * zoom + 64 : undefined }}>
        <ul ref={tree} className={styles.tree} style={{ transform: `translateX(-50%) scale(${zoom})` }}>
          <GraphNode node={root} selected={selected} onSelect={onSelect} onDetails={onDetails} openDetailPath={openDetailPath} />
        </ul>
      </nav>
    </div>
    <p className={styles.caption}>连线表示编排层级；顺序节点按从左到右的子节点顺序执行，路由与循环遵循各自条件。</p>
  </>;
}

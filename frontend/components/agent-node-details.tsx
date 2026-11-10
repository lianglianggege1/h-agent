"use client";

import type { ReactNode } from "react";
import type { AgentTopologyNode } from "@/lib/agents";
import type { TopologyEntry } from "@/lib/agent-topology";
import styles from "./agent-node-details.module.css";

const labels: Record<string, string> = {
  AI_AGENT: "AI 节点", NON_AI_AGENT: "逻辑节点", HUMAN_IN_THE_LOOP: "人工确认",
  SEQUENCE: "顺序执行", PARALLEL: "并行执行", LOOP: "循环", ROUTER: "条件路由", STAR: "主管调度",
};
const nullableBoolean = (value: boolean | null | undefined, yes: string, no: string) => value == null ? "未提供" : value ? yes : no;

function Field({ name, children }: { name: string; children: ReactNode }) {
  return <div className={styles.field}><dt>{name}</dt><dd>{children ?? "未提供"}</dd></div>;
}

export function AgentNodeDetails({ entry, entries, stateKeys, onSelect, onClose }: {
  entry: TopologyEntry; entries: TopologyEntry[]; stateKeys: Array<{ key: string; type: string }>;
  onSelect: (path: string) => void; onClose?: () => void;
}) {
  const node: AgentTopologyNode = entry.node;
  const inputs = node.inputArguments ?? node.inputKeys.map(name => ({ name, type: stateKeys.find(key => key.key === name)?.type ?? null }));
  const children = entries.filter(candidate => candidate.parentPath === entry.path);
  return <div className={styles.root}>
    <div className={styles.header} data-node-details-header>
      <div className={styles.title}><h2>{node.name || node.nodeId}</h2><p>{node.decisionRouter ? "决策路由" : labels[node.topology] ?? "节点"} · {node.topology}</p></div>
      <div className={styles.actions}>
        {entry.parentPath ? <button type="button" onClick={() => onSelect(entry.parentPath!)}>上级节点</button> : null}
        {onClose ? <button className={styles.close} type="button" aria-label="关闭节点详情" onClick={onClose}>×</button> : null}
      </div>
    </div>
    <div className={styles.body}>
      {node.description ? <p className={styles.description}>{node.description}</p> : null}
      <dl className={styles.fields}>
        <Field name="节点 ID"><code>{node.nodeId || "未提供"}</code></Field>
        <Field name="执行方式">{nullableBoolean(node.async, "异步", "同步")}</Field>
        <Field name="节点类型"><code>{node.implementationClass || node.type || "未提供"}</code></Field>
        <Field name="规划器"><code>{node.plannerClass || node.plannerType || "未提供"}</code></Field>
        <Field name="输出键"><code>{node.outputKey || "未声明"}</code></Field>
        <Field name="返回类型"><code>{node.returnType || "未提供"}</code></Field>
      </dl>
      <div className={styles.group}><h3>输入参数</h3>{inputs.length ? <dl className={styles.inputs}>{inputs.map(input => <div key={input.name}><dt><code>{input.name}</code></dt><dd><code>{input.type || "类型未提供"}</code></dd></div>)}</dl> : <p className={styles.muted}>未声明输入参数</p>}</div>
      {node.condition ? <div className={styles.group}><h3>进入条件</h3><p className={styles.condition}>{node.condition}</p></div> : null}
      {node.decisionRouter ? <div className={styles.group}><h3>决策路由</h3><dl className={styles.fields}>
        <Field name="选择方式">{node.decisionRouter.selectionMode === "MULTIPLE" ? "独立多选" : "单选"}</Field>
        <Field name="执行方式">{node.decisionRouter.dispatchMode === "PARALLEL" ? "并行执行" : "单节点执行"}</Field>
        <Field name="激活阈值">{node.decisionRouter.activationThreshold ?? "未设置"}</Field>
      </dl><p className={styles.muted}>{node.decisionRouter.selectionMode === "MULTIPLE" ? "每个候选节点独立判断是否参与；可能没有节点达到激活阈值。" : "选择参与概率最高的一个节点。"}</p></div> : null}
      {node.loop ? <div className={styles.group}><h3>循环控制</h3><dl className={styles.fields}>
        <Field name="最大迭代次数">{node.loop.maxIterations ?? "未提供"}</Field>
        <Field name="退出检查时机">{nullableBoolean(node.loop.testExitAtLoopEnd, "每轮结束后", "每轮开始前")}</Field>
        <Field name="退出条件">{node.loop.exitCondition || "未提供"}</Field>
      </dl></div> : null}
      {children.length ? <div className={styles.group}><h3>子节点 · {children.length}</h3><div className={styles.childLinks}>{children.map(child => <button key={child.path} type="button" onClick={() => onSelect(child.path)}>{child.node.name || child.node.nodeId}</button>)}</div>{node.topology === "SEQUENCE" ? <p className={styles.muted}>按结构列表中的顺序依次执行。</p> : null}</div> : null}
    </div>
  </div>;
}

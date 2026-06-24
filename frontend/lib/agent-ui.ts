import type { AgentTopologyNode } from "./agents";
import type { UiAgentStep } from "./chat-message-state";

const CONTAINER_TOPOLOGIES = new Set(["SEQUENCE", "ROUTER", "LOOP", "PARALLEL", "STAR"]);

export function agentStepStatusText(status: UiAgentStep["status"]) {
  if (status === "running") return "执行中";
  if (status === "completed") return "已完成";
  return "失败";
}

export function visibleAgentSteps(steps: UiAgentStep[]) {
  return steps
    .filter((step) => (step.depth ?? 0) > 0)
    .filter((step) => !CONTAINER_TOPOLOGIES.has(step.topology))
    .sort((left, right) => left.sequence - right.sequence);
}

export function topologyLabel(topology: string | null) {
  if (topology === "AI_AGENT") return "AI";
  if (topology === "NON_AI_AGENT") return "Non-AI";
  if (topology === "HUMAN_IN_THE_LOOP") return "Human";
  if (topology === "SEQUENCE") return "Sequence";
  if (topology === "PARALLEL") return "Parallel";
  if (topology === "LOOP") return "Loop";
  if (topology === "ROUTER") return "Router";
  if (topology === "STAR") return "Star";
  return "Agent";
}

export function topologyTone(topology: string | null) {
  if (topology === "AI_AGENT") return "ai";
  if (topology === "NON_AI_AGENT") return "nonai";
  if (topology === "HUMAN_IN_THE_LOOP") return "human";
  if (topology === "SEQUENCE") return "seq";
  if (topology === "PARALLEL") return "par";
  if (topology === "LOOP") return "loop";
  if (topology === "ROUTER") return "rtr";
  if (topology === "STAR") return "star";
  return "nonai";
}

export function collectTopologyLegend(root: AgentTopologyNode) {
  const seen = new Set<string>();
  const items: Array<{ topology: string; label: string; tone: string }> = [];

  function visit(node: AgentTopologyNode) {
    const topology = node.topology || "AGENT";
    if (!seen.has(topology)) {
      seen.add(topology);
      items.push({ topology, label: topologyLabel(node.topology), tone: topologyTone(node.topology) });
    }
    node.children.forEach(visit);
  }

  visit(root);
  return items;
}

export function visibleTopologyStateKeys(keys: Array<{ key: string }>) {
  return keys.filter((item) => item.key && !item.key.startsWith("@"));
}

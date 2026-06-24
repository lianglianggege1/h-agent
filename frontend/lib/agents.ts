import { apiFetch } from "./http";

export type AgentSummary = {
  agentId: string;
  displayName: string;
  domain: string;
  tags: string[];
  summary: string;
  runtimeType: string;
  enabled: boolean;
};

export type AgentTopologyLoop = {
  maxIterations: number | null;
  exitCondition: string | null;
  testExitAtLoopEnd: boolean | null;
};

export type AgentTopologyNode = {
  nodeId: string;
  name: string;
  topology: string;
  type: string | null;
  description: string | null;
  returnType: string | null;
  plannerType: string | null;
  outputKey: string | null;
  inputKeys: string[];
  condition: string | null;
  async: boolean | null;
  loop: AgentTopologyLoop | null;
  children: AgentTopologyNode[];
};

export type AgentTopology = {
  agent: AgentSummary;
  root: AgentTopologyNode;
  stateKeys: Array<{ key: string; type: string; color: string }>;
};

export function listAgents() {
  return apiFetch<AgentSummary[]>("/api/agents");
}

export function getAgentTopology(agentId: string) {
  return apiFetch<AgentTopology>(`/api/agents/${encodeURIComponent(agentId)}/topology`);
}

import type { AgentTopologyNode } from './agents';

export type TopologyEntry = { path: string; parentPath: string | null; depth: number; node: AgentTopologyNode };

export function flattenTopology(root: AgentTopologyNode): TopologyEntry[] {
  const result: TopologyEntry[] = [];
  function visit(node: AgentTopologyNode, path: string, parentPath: string | null, depth: number) {
    result.push({ path, parentPath, depth, node });
    node.children.forEach((child, index) => visit(child, `${path}.${index}`, path, depth + 1));
  }
  visit(root, '0', null, 0);
  return result;
}

export function stateKeyUsage(entries: TopologyEntry[], key: string) {
  return {
    producers: entries.filter(({ node }) => node.outputKey === key),
    consumers: entries.filter(({ node }) => node.inputKeys.includes(key)),
  };
}

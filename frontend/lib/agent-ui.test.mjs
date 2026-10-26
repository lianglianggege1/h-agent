import assert from "node:assert/strict";
import { test } from "node:test";
import {
  collectTopologyLegend,
  currentAgentStepText,
  isVisibleSubagentTurn,
  visibleAgentSteps,
  visibleTopologyStateKeys,
  decisionRouterSummary,
} from "./agent-ui.ts";

test("decisionRouterSummary explains multi-select threshold and parallel dispatch", () => {
  assert.deepEqual(decisionRouterSummary({
    selectionMode: "MULTIPLE", activationThreshold: 0.75, dispatchMode: "PARALLEL",
  }), ["决策路由 · 独立多选", "参与概率 ≥ 0.75", "命中专家并行执行；可以全部不选"]);
});

test("decisionRouterSummary explains single choice without a threshold", () => {
  assert.deepEqual(decisionRouterSummary({
    selectionMode: "SINGLE", activationThreshold: null, dispatchMode: "SINGLE",
  }), ["决策路由 · 单选", "选择概率最高的一个 Agent"]);
  assert.deepEqual(decisionRouterSummary(null), []);
});

test("visibleAgentSteps keeps only child executable agent states", () => {
  const steps = [
    {
      invocationId: "root",
      nodeId: "root",
      nodeName: "CarRentalAssistant",
      topology: "SEQUENCE",
      status: "running",
      depth: 0,
      sequence: 1,
    },
    {
      invocationId: "router",
      nodeId: "router",
      nodeName: "客户信息分流",
      topology: "ROUTER",
      status: "completed",
      depth: 1,
      sequence: 2,
    },
    {
      invocationId: "extract",
      nodeId: "extract",
      nodeName: "客户信息提取",
      topology: "AI_AGENT",
      status: "completed",
      depth: 1,
      sequence: 3,
    },
    {
      invocationId: "ask",
      nodeId: "ask",
      nodeName: "追问客户信息",
      topology: "HUMAN_IN_THE_LOOP",
      status: "running",
      depth: 2,
      sequence: 4,
    },
  ];

  assert.deepEqual(visibleAgentSteps(steps).map((step) => step.invocationId), ["extract", "ask"]);
});

test("visibleAgentSteps keeps executable agent states without depth metadata", () => {
  const steps = [
    {
      invocationId: "router",
      nodeId: "router",
      nodeName: "专家分流",
      topology: "ROUTER",
      status: "running",
      depth: null,
      sequence: 1,
    },
    {
      invocationId: "legal",
      nodeId: "legal",
      nodeName: "法律专家",
      topology: "AI_AGENT",
      status: "running",
      depth: null,
      sequence: 2,
    },
  ];

  assert.deepEqual(visibleAgentSteps(steps).map((step) => step.invocationId), ["legal"]);
});

test("currentAgentStepText describes the active executable agent", () => {
  const steps = [
    {
      invocationId: "medical",
      nodeId: "medical",
      nodeName: "医疗专家",
      topology: "AI_AGENT",
      status: "completed",
      depth: 1,
      sequence: 1,
    },
    {
      invocationId: "technical",
      nodeId: "technical",
      nodeName: "技术专家",
      topology: "AI_AGENT",
      status: "running",
      depth: 1,
      sequence: 2,
    },
  ];

  assert.equal(currentAgentStepText(steps), "正在执行：技术专家");
});

test("isVisibleSubagentTurn hides empty assistant placeholders", () => {
  const emptyAssistantTurn = {
    kind: "assistant",
    id: "empty-assistant",
    reasoning: null,
    answer: "",
    blocked: null,
    agentSteps: [],
    resources: [],
  };

  assert.equal(isVisibleSubagentTurn(emptyAssistantTurn), false);
  assert.equal(isVisibleSubagentTurn({ ...emptyAssistantTurn, reasoning: "正在拆解问题" }), true);
});

test("collectTopologyLegend follows topology order from the system tree", () => {
  const root = {
    nodeId: "root",
    name: "root",
    topology: "SEQUENCE",
    type: null,
    description: null,
    returnType: null,
    plannerType: null,
    outputKey: null,
    inputKeys: [],
    condition: null,
    async: false,
    loop: null,
    children: [
      {
        nodeId: "extract",
        name: "extract",
        topology: "AI_AGENT",
        type: null,
        description: null,
        returnType: null,
        plannerType: null,
        outputKey: "customerInfo",
        inputKeys: ["message"],
        condition: null,
        async: false,
        loop: null,
        children: [],
      },
      {
        nodeId: "gate",
        name: "gate",
        topology: "ROUTER",
        type: null,
        description: null,
        returnType: null,
        plannerType: null,
        outputKey: null,
        inputKeys: [],
        condition: null,
        async: false,
        loop: null,
        children: [],
      },
    ],
  };

  assert.deepEqual(collectTopologyLegend(root).map((item) => item.label), ["Sequence", "AI", "Router"]);
});

test("visibleTopologyStateKeys hides framework annotation keys", () => {
  const keys = [
    { key: "@MemoryId" },
    { key: "message" },
    { key: "customerInfo" },
  ];

  assert.deepEqual(visibleTopologyStateKeys(keys).map((item) => item.key), ["message", "customerInfo"]);
});

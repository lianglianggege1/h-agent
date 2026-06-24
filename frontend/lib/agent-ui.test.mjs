import assert from "node:assert/strict";
import { test } from "node:test";
import { collectTopologyLegend, visibleAgentSteps, visibleTopologyStateKeys } from "./agent-ui.ts";

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

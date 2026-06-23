import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { test } from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === "./http" && context.parentURL?.endsWith("/agents.ts")) {
      return nextResolve("./http.ts", context);
    }

    return nextResolve(specifier, context);
  },
});

const { getAgentTopology, listAgents } = await import("./agents.ts");

test("listAgents calls agent catalog endpoint", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;

  globalThis.fetch = async (path) => {
    capturedPath = path;
    return Response.json({
      code: 0,
      message: "ok",
      data: [
        {
          agentId: "car-rental-assistant",
          displayName: "租车助手",
          domain: "出行",
          tags: ["租车"],
          summary: "处理租车咨询",
          runtimeType: "AGENTIC_SYNC",
          enabled: true,
        },
      ],
    });
  };

  try {
    const result = await listAgents();

    assert.equal(capturedPath, "/api/agents");
    assert.equal(result[0].agentId, "car-rental-assistant");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("getAgentTopology encodes agent id", async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];

  globalThis.fetch = async (path) => {
    calls.push(path);
    return Response.json({
      code: 0,
      message: "ok",
      data: { agent: {}, root: {}, stateKeys: [] },
    });
  };

  try {
    await getAgentTopology("car/rental");

    assert.equal(calls[0], "/api/agents/car%2Frental/topology");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

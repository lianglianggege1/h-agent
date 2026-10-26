import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";

const require = createRequire(import.meta.url);
const React = require("react");
const { createRoot } = require("react-dom/client");
const compiled = ts.transpileModule(readFileSync(new URL("../components/agui-progress.tsx", import.meta.url), "utf8"), {
  compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
}).outputText;
const product = { exports: {} };
new Function("require", "module", "exports", compiled)(require, product, product.exports);
const { ProgressCard } = product.exports;

test("process streams expanded, collapses on answer, and preserves the reader's choice", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>");
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const root = createRoot(dom.window.document.getElementById("root"));
  const content = { runId: "run", status: "RUNNING", reasoning: "", answerStarted: false,
    startedAt: 1000, entries: [{ id: "r1", type: "reasoning", chunks: ["先查资料"] }], steps: [] };
  const render = async (updates) => {
    Object.assign(content, updates);
    await React.act(async () => root.render(React.createElement(ProgressCard, { content: { ...content } })));
  };
  const toggle = () => dom.window.document.querySelector("button[aria-expanded]");
  try {
    await render({});
    assert.equal(toggle()?.getAttribute("aria-expanded"), "true", "Thinking starts visible");
    assert.match(dom.window.document.body.textContent, /先查资料/);
    await render({ entries: [{ id: "r1", type: "reasoning", chunks: ["先查资料", "再整理"] }] });
    assert.match(dom.window.document.body.textContent, /先查资料再整理/);
    await render({ answerStarted: true, processEndedAt: 9000 });
    assert.equal(toggle().getAttribute("aria-expanded"), "false");
    assert.match(toggle().textContent, /8 秒/);
    await render({ steps: [{ id: "s1", name: "web_search", kind: "tool", status: "running" }] });
    assert.equal(toggle().getAttribute("aria-expanded"), "false", "Later tools do not reopen the process");
    await React.act(async () => toggle().click());
    await render({ status: "COMPLETED", steps: [{ id: "s1", name: "web_search", kind: "tool", status: "completed" }] });
    assert.equal(toggle().getAttribute("aria-expanded"), "true", "Manual expansion survives completion");
    await render({ runId: "next", answerStarted: false, status: "RUNNING", processEndedAt: undefined });
    await React.act(async () => toggle().click());
    await render({ entries: [{ id: "r1", type: "reasoning", chunks: ["下一轮"] }] });
    assert.equal(toggle().getAttribute("aria-expanded"), "false", "Manual collapse survives new deltas");
    await render({ runId: "history", status: "COMPLETED", answerStarted: false });
    assert.equal(toggle().getAttribute("aria-expanded"), "false", "History starts collapsed");
    await render({ runId: "failed", status: "FAILED", steps: [{ id: "error", name: "任务失败", status: "failed", result: "连接中断" }] });
    assert.match(dom.window.document.querySelector('[role="alert"]').textContent, /连接中断/);
    assert.equal(toggle().getAttribute("aria-expanded"), "false", "Failure does not expose the entire process");
    await render({ status: "INTERRUPTED", steps: [] });
    assert.match(dom.window.document.querySelector('[role="alert"]').textContent, /执行服务已中断/);
  } finally {
    await React.act(async () => root.unmount());
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
  }
});

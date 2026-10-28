import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";
import { orderProductMessages } from "./agui-messages.ts";

const require = createRequire(import.meta.url);
const React = require("react");
const { createRoot } = require("react-dom/client");
const compiled = ts.transpileModule(readFileSync(new URL("../components/agui-progress.tsx", import.meta.url), "utf8"), {
  compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
}).outputText;
const product = { exports: {} };
new Function("require", "module", "exports", compiled)(require, product, product.exports);
const { ProgressCard } = product.exports;

test("editing a message the next day shows only the new answer's nine seconds of thinking", () => {
  const process = (id, start, end, thought) => ({ id, role: "activity", activityType: "h-agent.progress", content: {
    runId: id, status: "COMPLETED", answerStarted: true, anchorMessageId: "user",
    startedAt: start, processEndedAt: end, steps: [],
    entries: [{ id: "reason", type: "reasoning", chunks: [thought] }],
  } });
  const original = process("old-run", 0, 9000, "旧问题的思考");
  const revised = process("edited-run", 70_431_000, 70_440_000, "修改后重新思考");
  const history = [{ id: "user", role: "user", content: "修改后的问题" },
    { id: "answer", role: "assistant", content: "修改后重新生成的回答" }];
  const label = (messages) => {
    const progress = orderProductMessages(messages).find((message) => message.activityType === "h-agent.progress");
    const html = require("react-dom/server").renderToStaticMarkup(React.createElement(ProgressCard, {
      content: product.exports.progressSchema.parse(progress.content),
    }));
    const dom = new JSDOM(html);
    try { return dom.window.document.querySelector("button").textContent.trim(); }
    finally { dom.window.close(); }
  };
  assert.equal(label([...history, revised]), "思考了 9 秒", "Control: timestamps are in milliseconds");
  assert.equal(label([...history, original, revised]), "思考了 9 秒",
    "Reusing the original user message must not count yesterday's idle time or old thinking");
});

test("continuing after a long pause counts actual thinking intervals, including through schema parsing", () => {
  const before = { id: "p1", role: "activity", activityType: "h-agent.progress", content: {
    runId: "before", status: "PAUSED", anchorMessageId: "u", startedAt: 1000, processEndedAt: 4000,
    answerStarted: true, entries: [{ id: "r", type: "reasoning", chunks: ["第一段"] }], steps: [],
  } };
  const after = { id: "p2", role: "activity", activityType: "h-agent.progress", content: {
    runId: "after", status: "COMPLETED", anchorMessageId: "u", startedAt: 70_440_000, processEndedAt: 70_445_000,
    answerStarted: true, entries: [{ id: "r", type: "reasoning", chunks: ["续写思考"] }], steps: [],
  } };
  const projected = orderProductMessages([{ id: "u", role: "user", content: "问题" }, before, after]);
  const content = product.exports.progressSchema.parse(projected.find((message) => message.role === "activity").content);
  const html = require("react-dom/server").renderToStaticMarkup(React.createElement(ProgressCard, { content }));
  assert.match(html, /思考了 8 秒/);
  assert.ok(content.entries.some((entry) => entry.chunks?.includes("第一段")), "A real continuation retains its earlier process");
});

test("completed legacy progress without a finish timestamp never substitutes today's wall clock", () => {
  const html = require("react-dom/server").renderToStaticMarkup(React.createElement(ProgressCard, { content: {
    runId: "legacy", status: "COMPLETED", startedAt: 1000, answerStarted: true, steps: [],
    entries: [{ id: "reason", type: "reasoning", chunks: ["历史思考"] }],
  } }));
  assert.match(html, /思考过程/);
  assert.doesNotMatch(html, /\d+ 秒/);
});

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

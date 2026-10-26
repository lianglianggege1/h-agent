import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";
import * as memoryState from "./harness-memory-state.ts";
const require = createRequire(import.meta.url);
const documentFor = (content, revision = 1) => ({ content, revision, exists: true, updatedAt: null });
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

test("agent selection isolates requests, drafts and late responses", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>", { url: "http://localhost/me/memory" });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const orderRead = deferred(), pendingSave = deferred(), reads = [], writes = [];
  let catalogReads = 0;
  const router = { replace() { assert.fail("unexpected login redirect"); } };
  const stubs = {
    "next/navigation": { useRouter: () => router },
    "next/link": { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) },
    "@/components/markdown-content": { MarkdownContent: ({ content }) => React.createElement("p", null, content) },
    "@/lib/auth": { getCurrentUser: async () => ({}) },
    "@/lib/session": { savePostLoginRedirect() {} },
    "@/lib/harness-memory-state": memoryState,
    "@/lib/harness-memory": {
      HARNESS_MEMORY_MAX_BYTES: 65_536, utf8ByteLength: (value) => new TextEncoder().encode(value).length,
      extractMemorySections: () => [], isHarnessMemoryConflict: () => false,
      listHarnessMemoryAgents: async () => {
        catalogReads++;
        return [{ agentId: "harness-agent", displayName: "协作 Agent" },
          { agentId: "order-processing-assistant", displayName: "订单处理助手" }];
      },
      getHarnessMemory: async (id) => {
        reads.push(id);
        return id === "harness-agent" ? documentFor("主助手记忆", 3) : orderRead.promise;
      },
      saveHarnessMemory: (...args) => { writes.push(args); return pendingSave.promise; },
    },
  };
  const source = readFileSync(new URL("../app/me/memory/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX,
    module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText;
  const product = { exports: {} };
  new Function("require", "module", "exports", compiled)((id) => stubs[id] ?? require(id), product, product.exports);
  const root = createRoot(dom.window.document.getElementById("root"));
  const select = async (id) => React.act(async () => {
    const element = dom.window.document.querySelector("select");
    element.value = id;
    element.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
  });
  const click = async (label) => React.act(async () => {
    [...dom.window.document.querySelectorAll("button")].find((button) => button.textContent === label).click();
  });
  try {
    await React.act(async () => root.render(React.createElement(product.exports.default)));
    assert.match(dom.window.document.body.textContent, /主助手记忆/);
    await select("order-processing-assistant");
    assert.doesNotMatch(dom.window.document.body.textContent, /主助手记忆/);
    await select("harness-agent");
    await React.act(async () => orderRead.resolve(documentFor("订单记忆", 9)));
    assert.match(dom.window.document.body.textContent, /主助手记忆/);
    assert.doesNotMatch(dom.window.document.body.textContent, /订单记忆/);
    assert.equal(catalogReads, 1, "switching must not reload the catalog or reset selection");
    await click("编辑");
    const textarea = dom.window.document.querySelector("textarea");
    await React.act(async () => {
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(textarea, "主助手草稿");
      textarea.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
    dom.window.confirm = () => false;
    await select("order-processing-assistant");
    assert.equal(dom.window.document.querySelector("select").value, "harness-agent");
    assert.equal(dom.window.document.querySelector("textarea").value, "主助手草稿");
    await click("保存");
    assert.deepEqual(writes, [["主助手草稿", 3, "harness-agent"]]);
    assert.equal(dom.window.document.querySelector("select").disabled, true);
    await React.act(async () => pendingSave.resolve(documentFor("主助手草稿", 4)));
    await select("order-processing-assistant");
    assert.match(dom.window.document.body.textContent, /订单记忆/);
    await click("编辑");
    assert.equal(dom.window.document.querySelector("textarea").value, "订单记忆");
    assert.deepEqual(reads, ["harness-agent", "order-processing-assistant", "harness-agent", "order-processing-assistant"]);
  } finally {
    await React.act(async () => root.unmount());
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
  }
});

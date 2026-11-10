import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";
const require = createRequire(import.meta.url);

test("user knowledge page works without prompts and expands ordered source chunks", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>", { url: "http://localhost/me/knowledge?promptId=999" });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const router = { replace() { assert.fail("unexpected redirect"); } };
  let listings = 0;
  const stubs = {
    "next/navigation": { useRouter: () => router },
    "next/link": { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) },
    "@/lib/auth": { getCurrentUser: async () => ({ id: 7 }) },
    "@/lib/session": { savePostLoginRedirect() {} },
    "@/lib/knowledge": {
      listKnowledgeDocuments: async (...args) => {
        assert.deepEqual(args, []); listings++;
        return [{ id: 1, fileName: "协作经验", sourceType: "AGENT", status: "COMPLETED", fileType: "txt",
          segmentCount: 2, charCount: 20, fileSize: null, errorMsg: null, createdAt: "2026-11-09T10:00:00" }];
      },
      listKnowledgeSegments: async () => [
        { text: "first fact", metadata: JSON.stringify({ index: "0" }) },
        { text: "legacy fact", metadata: "{}" },
      ],
      createManualKnowledge: async () => 1, uploadKnowledgeDocument: async () => 1,
      deleteKnowledgeDocument: async () => null,
    },
  };
  const source = readFileSync(new URL("../app/me/knowledge/page.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS } }).outputText;
  const module = { exports: {} };
  const componentRequire = (id) => {
    if (id === "@/lib/system-prompts") assert.fail("knowledge page must not load or create prompts");
    return stubs[id] ?? require(id);
  };
  const container = dom.window.document.getElementById("root");
  const root = createRoot(container);
  try {
    new Function("require", "module", "exports", compiled)(componentRequire, module, module.exports);
    await React.act(async () => { root.render(React.createElement(module.exports.default)); });
    assert.equal(listings, 1);
    assert.equal(dom.window.location.search, "");
    assert.match(container.textContent, /Agent 沉淀/);
    assert.doesNotMatch(container.textContent, /SystemPrompt/);
    const button = [...container.querySelectorAll("button")].find((b) => b.textContent === "查看切片");
    await React.act(async () => { button.click(); });
    assert.match(container.textContent, /first fact/);
    assert.match(container.textContent, /原顺序不可用/);
  } finally {
    await React.act(async () => root.unmount());
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
    dom.window.close();
  }
});

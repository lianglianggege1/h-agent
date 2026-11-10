import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);

test("the real preview keeps the draft mounted, switches mixed files, renders DOCX safely, retries failures and opens a mobile dialog", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>", { url: "http://localhost", pretendToBeVisual: true });
  const saved = new Map();
  const observers = [];
  const docx = readFileSync(new URL("./fixtures/preview.docx", import.meta.url));
  const requests = [];
  let fail = false;
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, DOMParser: dom.window.DOMParser,
    IS_REACT_ACT_ENVIRONMENT: true,
    ResizeObserver: class { constructor(callback) { this.callback = callback; observers.push(this); } observe() {} disconnect() {} },
    fetch: async (url) => {
      requests.push(url);
      if (fail) return new Response("", { status: 404 });
      if (url === "/docx") return new Response(docx);
      return new Response("# Markdown 预览\n\n正文 **加粗** <script>alert(1)</script>");
    },
  })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const modules = new Map();
  function load(name) {
    if (modules.has(name)) return modules.get(name).exports;
    const componentModule = { exports: {} };
    modules.set(name, componentModule);
    const source = readFileSync(new URL(`../components/${name}.tsx`, import.meta.url), "utf8");
    const code = ts.transpileModule(source, { compilerOptions: {
      jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true,
    } }).outputText;
    new Function("require", "module", "exports", code)((id) => {
      if (id.endsWith(".css")) return {};
      if (id === "@/lib/file-preview") return require("./file-preview.ts");
      if (id === "@/lib/markdown-links") return require("./markdown-links.ts");
      if (id === "mammoth") return require("mammoth/mammoth.browser");
      if (id.startsWith("./")) return load(id.slice(2));
      return require(id);
    }, componentModule, componentModule.exports);
    return componentModule.exports;
  }
  const { FilePreviewProvider, useFilePreview } = load("file-preview");
  const files = [
    { id: "md", fileName: "notes.md", mimeType: "text/markdown", viewUrl: "/md", downloadUrl: "/md/download", fileSize: null },
    { id: "docx", fileName: "report.docx", mimeType: "application/octet-stream", viewUrl: "/docx", downloadUrl: "/docx/download", fileSize: null },
    { id: "ppt", fileName: "slides.pptx", mimeType: "application/octet-stream", viewUrl: "/ppt", downloadUrl: "/ppt/download", fileSize: null },
  ];
  function Chat() {
    const preview = useFilePreview();
    return React.createElement("div", null,
      React.createElement("textarea", { "aria-label": "聊天草稿", defaultValue: "未发送的草稿" }),
      React.createElement("button", { onClick: () => preview.open(files, "md") }, "打开附件"));
  }
  const root = createRoot(document.getElementById("root"));
  const findButton = (label) => [...document.querySelectorAll("button")].find(button => button.textContent === label || button.getAttribute("aria-label") === label);
  const click = async label => React.act(async () => findButton(label).click());
  const settle = async () => {
    for (let tick = 0; tick < 8; tick++) await React.act(async () => new Promise(resolve => setTimeout(resolve, 10)));
  };
  try {
    await React.act(async () => root.render(React.createElement(FilePreviewProvider, null, React.createElement(Chat))));
    const draft = document.querySelector("textarea");
    const trigger = findButton("打开附件");
    trigger.focus();
    await click("打开附件");
    await settle();
    assert.ok(document.querySelector('[role="region"][aria-label="文件预览"]'));
    assert.ok(document.querySelector("h1"));
    assert.equal(document.querySelector("script"), null);
    assert.equal(document.querySelector("textarea"), draft);
    assert.equal(draft.disabled, false);
    assert.equal(draft.value, "未发送的草稿");
    await click("查看源码");
    assert.match(document.querySelector("pre").textContent, /# Markdown/);
    await click("下一个 →");
    await settle();
    assert.match(document.body.textContent, /中文文档预览/);
    assert.match(document.querySelector("td").textContent, /表格内容/);
    assert.match(document.querySelector("img").src, /^data:image\/png;base64,/);
    assert.equal(document.querySelector('a[href^="javascript:"]'), null);
    assert.equal(document.querySelector('[aria-label="下载 report.docx"]').getAttribute("href"), "/docx/download");
    await click("下一个 →");
    assert.match(document.body.textContent, /此格式暂不支持预览/);
    assert.equal(requests.includes("/ppt"), false);
    await click("关闭预览");
    assert.equal(document.activeElement, trigger);
    fail = true;
    await click("打开附件");
    await settle();
    assert.match(document.body.textContent, /文件不存在或已被清理/);
    fail = false;
    await click("重试");
    await settle();
    assert.ok(document.querySelector("h1"));
    await React.act(async () => observers[0].callback([{ contentRect: { width: 390 } }]));
    await settle();
    const dialog = document.querySelector('[role="dialog"][aria-modal="true"]');
    assert.ok(dialog);
    assert.equal(dialog.parentElement, document.body, "Mobile preview escapes the chat layout to cover the viewport");
    assert.ok(draft.closest("[inert]"));
    await React.act(async () => document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true })));
    assert.equal(document.querySelector('[aria-label="文件预览"]'), null);
    assert.equal(draft.closest("[inert]"), null);
    assert.equal(draft.value, "未发送的草稿");

    // A child-agent drawer owns another provider while the main chat stays mounted.
    await React.act(async () => root.render(React.createElement("div", null,
      ...["主对话", "子对话"].map(label => React.createElement("section", { key: label, "aria-label": label },
        React.createElement(FilePreviewProvider, null, React.createElement(Chat)))))));
    for (const label of ["主对话", "子对话"]) {
      const button = document.querySelector(`section[aria-label="${label}"] button`);
      button.focus();
      await React.act(async () => button.click());
      await settle();
    }
    assert.equal(document.querySelectorAll('[aria-label="文件预览"]').length, 2);
    await React.act(async () => document.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true })));
    assert.equal(document.querySelectorAll('[aria-label="文件预览"]').length, 1, "Escape closes only the preview containing focus");
    assert.ok(document.querySelector('section[aria-label="主对话"] [aria-label="文件预览"]'));
  } finally {
    await React.act(async () => root.unmount());
    dom.window.close();
    for (const [key, descriptor] of saved) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; }
  }
});

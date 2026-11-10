import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);

test("document synchronization stays invisible and only Jev low scores show manual review", async () => {
  const dom = new JSDOM("<div id='root'></div>", {
    url: "http://localhost",
    pretendToBeVisual: true,
  });
  const saved = new Map();
  for (const [key, value] of Object.entries({
    window: dom.window,
    document: dom.window.document,
    navigator: dom.window.navigator,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, {
      configurable: true,
      writable: true,
      value,
    });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  let tasks = [];
  const requests = [];
  const api = {
    documentTasks: async (session) => {
      requests.push(["tasks", session]);
      return tasks;
    },
    reviewDocument: async () => ({ ...tasks[0], pages: [] }),
  };
  const code = ts.transpileModule(
    readFileSync(
      new URL("../components/document-panel.tsx", import.meta.url),
      "utf8",
    ),
    {
      compilerOptions: {
        jsx: ts.JsxEmit.ReactJSX,
        module: ts.ModuleKind.CommonJS,
        esModuleInterop: true,
      },
    },
  ).outputText;
  const componentModule = { exports: {} };
  new Function("require", "module", "exports", code)(
    (id) => (id === "./file-preview" ? { useFilePreview: () => null } : id === "@/lib/documents" ? api : require(id)),
    componentModule,
    componentModule.exports,
  );
  const root = createRoot(document.getElementById("root"));
  const updates = [];
  const onUpdated = async (tasks) => {
    updates.push(tasks);
  };
  try {
    await React.act(async () => {
      root.render(
        React.createElement(componentModule.exports.DocumentTaskSync, {
          session: "empty",
          chatRunning: false,
          onUpdated,
        }),
      );
    });
    assert.equal(document.getElementById("root").innerHTML, "");
    tasks = [
      {
        taskId: "doc",
        fileName: "扫描件.pdf",
        status: "WAITING_CONFIRMATION",
        kind: "REVIEW",
        revision: 1,
      },
    ];
    await React.act(async () => {
      root.render(
        React.createElement(componentModule.exports.DocumentTaskSync, {
          session: "with-file",
          chatRunning: false,
          onUpdated,
        }),
      );
    });
    assert.equal(document.getElementById("root").innerHTML, "");
    assert.equal(updates.at(-1)[0].taskId, "doc");
    const block = {
      id: "b",
      text: "待核对文字",
      score: 0.3,
      canAccept: true,
      verdict: "scored",
      imageUrl: "/image",
    };
    api.reviewDocument = async () => ({
      ...tasks[0],
      pages: [{ number: 1, blocks: [block] }],
    });
    tasks[0].sessionId = "with-file";
    await React.act(async () =>
      root.render(
        React.createElement(componentModule.exports.DocumentTaskCard, {
          content: { task: tasks[0] },
        }),
      ),
    );
    assert.match(document.body.textContent, /扫描件.pdf/);
    assert.match(document.body.textContent, /质量复核/);
    assert.match(document.body.textContent, /复核分数 0.300/);
    assert.equal(document.querySelectorAll("textarea").length, 1);
    assert.doesNotMatch(
      document.body.textContent,
      /外部识别设置|允许本会话|启用后/,
    );
    for (const status of [
      "PARSING",
      "RECOGNIZED",
      "OCR_RUNNING",
      "VERIFYING",
      "VERIFICATION_FAILED",
      "VERIFIED",
    ]) {
      await React.act(async () =>
        root.render(
          React.createElement(componentModule.exports.DocumentTaskCard, {
            content: { task: { ...tasks[0], status } },
          }),
        ),
      );
      assert.equal(document.querySelectorAll("textarea").length, 0, status);
    }
    assert.ok(requests.every(([type]) => type === "tasks"));
  } finally {
    await React.act(async () => root.unmount());
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
});

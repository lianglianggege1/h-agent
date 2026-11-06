import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";
import { productHistoryMessages } from "./agui-messages.ts";
import {
  historyReferenceResources,
  selectHistoryReference,
} from "./reference-images.ts";

const require = createRequire(import.meta.url);

test("history picker displays an uploaded PDF by filename and selects it as a document", async () => {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost" });
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
  dom.window.HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const code = ts.transpileModule(
    readFileSync(
      new URL("../components/chat-attachment-picker.tsx", import.meta.url),
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
  const pickerModule = { exports: {} };
  new Function("require", "module", "exports", code)(
    require,
    pickerModule,
    pickerModule.exports,
  );
  const pdf = {
    id: "pdf",
    type: "FILE",
    role: "ATTACHMENT",
    fileName: "文档资料.pdf",
    mimeType: "application/pdf",
    viewUrl: "/pdf/view",
    downloadUrl: "/pdf/download",
    fileSize: 1024,
  };
  const resources = historyReferenceResources(
    productHistoryMessages([
      {
        id: "user",
        role: "user",
        messageType: "USER",
        content: "查看 PDF",
        resources: [pdf],
      },
    ]),
  );
  let selected = [];
  let olderRequests = 0;
  const root = createRoot(document.getElementById("root"));
  try {
    await React.act(async () =>
      root.render(
        React.createElement(pickerModule.exports.ChatAttachmentPicker, {
          resources,
          hasOlder: true,
          loadingOlder: false,
          onLoadOlder: () => olderRequests++,
          onUpload: () => {},
          onClose: () => {},
          onSelect: (resource) => {
            selected = selectHistoryReference(selected, resource);
          },
        }),
      ),
    );
    await React.act(async () =>
      [...document.querySelectorAll("button")]
        .find((button) => button.textContent === "从历史选择")
        .click(),
    );
    assert.doesNotMatch(document.body.textContent, /没有可引用的素材/);
    const button = document.querySelector(
      'button[aria-label="引用素材 文档资料.pdf"]',
    );
    assert.ok(button);
    assert.match(button.textContent, /文档资料\.pdf/);
    assert.match(button.textContent, /PDF/);
    assert.ok(button.querySelector("svg"));
    assert.equal(button.querySelector("img,video,audio"), null);
    await React.act(async () => button.click());
    assert.equal(selected[0].resourceId, "pdf");
    assert.equal(selected[0].role, "ATTACHMENT");
    assert.equal(selected[0].source, "HISTORY");
    await React.act(async () =>
      [...document.querySelectorAll("button")]
        .find((button) => button.textContent === "加载更早的历史素材")
        .click(),
    );
    assert.equal(olderRequests, 1);
  } finally {
    await React.act(async () => root.unmount());
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
});

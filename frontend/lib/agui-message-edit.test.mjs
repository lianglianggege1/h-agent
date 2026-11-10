import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);

test("inline editing supports cancel, multiline and IME without accidental resubmission", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>", { url: "http://localhost" });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true,
    requestAnimationFrame: (callback) => setTimeout(callback, 0) })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  // SDK presentation is covered in agui-user-message.test; this boundary exercises the editor's gestures.
  function UserMessage(props) {
    return props.children({ messageRenderer: React.createElement("p", null, "原文字"),
      toolbar: React.createElement(props.toolbar, null,
        React.createElement("button", { type: "button", "aria-label": "编辑消息", "data-testid": "copilot-edit-button",
          onClick: props.onEditMessage })) });
  }
  const code = ts.transpileModule(readFileSync(new URL("../components/agui-user-message.tsx", import.meta.url), "utf8"), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const component = { exports: {} };
  new Function("require", "module", "exports", code)((id) => id === "./file-preview" ? { useFilePreview: () => null } : id.endsWith(".module.css") ? {}
    : id === "@copilotkit/react-core/v2" ? { CopilotChatUserMessage: UserMessage } : require(id), component, component.exports);
  const writes = [];
  const root = createRoot(dom.window.document.getElementById("root"));
  const query = (label) => dom.window.document.querySelector(`[aria-label="${label}"]`);
  const input = async (text) => React.act(async () => {
    const textarea = query("修改消息文字");
    Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(textarea, text);
    textarea.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  });
  const key = async (options) => React.act(async () => query("修改消息文字")
    .dispatchEvent(new dom.window.KeyboardEvent("keydown", { bubbles: true, cancelable: true, ...options })));
  try {
    await React.act(async () => root.render(React.createElement(component.exports.UserMessageEditContext.Provider, {
      value: { latestId: "8", disabled: false, save: async (id, text) => { writes.push({ id, text }); } },
    }, React.createElement(component.exports.ReferencedUserMessage, {
      message: { id: "8", role: "user", content: "原文字" },
    }))));
    await React.act(async () => query("编辑消息").click());
    assert.equal(query("修改消息文字").rows, 1);
    assert.equal(query("保存并重新回答").disabled, true, "An unchanged message must not trigger another answer");
    await input("   ");
    assert.equal(query("保存并重新回答").disabled, true);
    await input("改正文字");
    await key({ key: "Enter", isComposing: true, keyCode: 229 });
    await key({ key: "Enter", shiftKey: true });
    assert.equal(writes.length, 0, "Composing Chinese or adding a line never submits");
    await key({ key: "Escape" });
    assert.equal(query("修改消息文字"), null);
    assert.equal(writes.length, 0);
    await React.act(async () => query("编辑消息").click());
    assert.equal(query("修改消息文字").value, "原文字", "Cancel discards the local edit");
    await input("改正文字\n第二行");
    await key({ key: "Enter" });
    assert.deepEqual(writes, [{ id: "8", text: "改正文字\n第二行" }]);
    assert.equal(query("修改消息文字"), null);
  } finally {
    await React.act(async () => root.unmount()); dom.window.close();
    for (const [key, value] of saved) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key]; }
  }
});

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);

test("pause offers continue for an empty draft and sends typed text as a new message", async () => {
  const dom = new JSDOM("<div id='root'></div>", { url: "http://localhost" });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement, getComputedStyle: dom.window.getComputedStyle,
    IS_REACT_ACT_ENVIRONMENT: true, ResizeObserver: class { observe() {} disconnect() {} },
    requestAnimationFrame: (cb) => setTimeout(cb, 0), cancelAnimationFrame: clearTimeout })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const oldCss = require.extensions[".css"];
  require.extensions[".css"] = () => {};
  const sdk = require("@copilotkit/react-core/v2");
  if (oldCss) require.extensions[".css"] = oldCss; else delete require.extensions[".css"];
  dom.window.HTMLCanvasElement.prototype.getContext = () => ({ measureText: () => ({ width: 0 }) });
  const subscriptions = new Set();
  const agent = { isRunning: true, messages: [{ id: "8", role: "user", content: "原请求" }],
    subscribe: (callbacks) => { subscriptions.add(callbacks); return { unsubscribe: () => subscriptions.delete(callbacks) }; },
    addMessages: (messages) => { agent.messages = [...agent.messages, ...messages]; },
    detachActiveRun: async () => { agent.isRunning = false; subscriptions.forEach((s) => s.onRunFinalized?.()); },
  };
  const requests = [];
  const runs = [];
  let pausedRunId = null;
  const copilotkit = { connectAgent: async () => {}, runAgent: async (request) => { runs.push(request.forwardedProps); } };
  function Chat({ chatView: View, onStop }) {
    const [value, onInputChange] = React.useState("");
    return React.createElement(View, { inputValue: value, onInputChange, isRunning: agent.isRunning, onStop });
  }
  Chat.View = ({ input: Input, inputValue, onInputChange, isRunning, onStop, onSubmitMessage }) =>
    React.createElement(Input, { value: inputValue, onChange: onInputChange, isRunning, onStop, onSubmitMessage });
  const stubs = {
    "@copilotkit/react-core/v2": { ...sdk, CopilotChat: Chat, useCopilotKit: () => ({ copilotkit }), useRenderTool: () => {},
      useAgent: () => {
        const [, update] = React.useReducer((n) => n + 1, 0);
        React.useEffect(() => { const sub = agent.subscribe({ onRunFinalized: update }); return () => sub.unsubscribe(); }, []);
        return { agent, isReady: true };
      } },
    "@/lib/http": { apiFetch: async (path, init) => {
      requests.push(path);
      if (init?.method === "POST" && path.endsWith("/pause")) pausedRunId = "paused-run";
      return { pausedRunId };
    } },
    "@/lib/chat-sessions": { getChatSessionMessages: async () => ({ messages: [], hasMore: false, nextBeforeSeq: null }) },
    "@/lib/resource-upload": {},
    "@/components/agui-cards": { ResourceReferenceContext: React.createContext(null) },
    "@/components/agui-user-message": { UserMessageEditContext: React.createContext(null) },
    "@/lib/agui-messages": { productHistoryMessages: (messages) => messages, orderProductMessages: (messages) => messages },
    "@/lib/agui-media-updates": { pendingMediaMessageIds: () => [] },
    "./agui-chat-thread.module.css": { default: {} }, "./chat-attachment-picker": {},
    "@/lib/reference-images": {}, "./order/order-card": { OrderReferenceContext: React.createContext(null) }, "./order/order-picker": {},
  };
  const compiled = ts.transpileModule(readFileSync(new URL("../components/agui-chat-thread.tsx", import.meta.url), "utf8"), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText;
  const product = { exports: {} };
  new Function("require", "module", "exports", compiled)((id) => stubs[id] ?? require(id), product, product.exports);
  const root = createRoot(dom.window.document.getElementById("root"));
  try {
    await React.act(async () => root.render(React.createElement(product.exports.AguiChatThread, { threadId: "thread" })));
    const pauseButton = dom.window.document.querySelector('[aria-label="暂停回答"]');
    assert.ok(pauseButton.querySelector("svg"));
    assert.equal(pauseButton.textContent.trim(), "", "The primary action is an icon, not another text pill");
    assert.equal(dom.window.document.querySelector('[data-testid="copilot-chat-input"] [aria-label="停止回答"]'), null,
      "The composer keeps one primary action instead of adjacent pause and stop buttons");
    const options = dom.window.document.querySelector('[aria-label="对话选项"]');
    const menu = options.closest("details");
    await React.act(async () => options.click());
    assert.equal(menu.open, true);
    assert.ok([...menu.querySelectorAll("button")].some((button) => button.textContent === "结束回答"));
    await React.act(async () => dom.window.document.dispatchEvent(new dom.window.Event("pointerdown", { bubbles: true })));
    assert.equal(menu.open, false, "Clicking outside closes the secondary actions");
    await React.act(async () => options.click());
    await React.act(async () => options.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true })));
    assert.equal(menu.open, false, "Escape dismisses the secondary actions");
    await React.act(async () => dom.window.document.querySelector('[aria-label="暂停回答"]').click());
    assert.ok(requests.includes("/api/chat/agui/threads/thread/pause"));
    const resume = dom.window.document.querySelector('[aria-label="继续回答"]');
    assert.ok(resume);
    assert.ok(resume.querySelector("svg"));
    assert.equal(resume.textContent.trim(), "");
    assert.equal(dom.window.document.querySelector("textarea").disabled, false);
    await React.act(async () => resume.click());
    assert.deepEqual(runs[0], { operation: "continue", sourceRunId: "paused-run" });
    assert.equal(agent.messages.length, 1, "Continue does not append a synthetic user message");

    // Reloading the paused state also restores the continue button.
    await React.act(async () => subscriptions.forEach((s) => s.onRunFinalized?.()));
    assert.ok(dom.window.document.querySelector('[aria-label="继续回答"]'));
    const input = dom.window.document.querySelector("textarea");
    await React.act(async () => {
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(input, "新的请求");
      input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
    assert.equal(dom.window.document.querySelector('[aria-label="继续回答"]'), null);
    await React.act(async () => input.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true })));
    assert.equal(agent.messages.at(-1).content, "新的请求");
    assert.deepEqual(runs[1], { resources: [] });
  } finally {
    await React.act(async () => root.unmount()); dom.window.close();
    for (const [key, value] of saved) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key]; }
  }
});

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";

const require = createRequire(import.meta.url);

// Render the product component with its real SDK input and connection lifecycle.
// Only application data and the SDK's provider-bound hooks/view are replaced.
test("idle order refresh keeps an unfinished message editable", async () => {
  const dom = new JSDOM("<!doctype html><div id='root'></div>", { url: "http://localhost" });
  const saved = new Map();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, HTMLElement: dom.window.HTMLElement,
    getComputedStyle: dom.window.getComputedStyle, IS_REACT_ACT_ENVIRONMENT: true,
    ResizeObserver: class { observe() {} disconnect() {} },
    requestAnimationFrame: (cb) => setTimeout(cb, 0), cancelAnimationFrame: clearTimeout })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  }
  const React = require("react");
  const { createRoot } = require("react-dom/client");
  const previousCssLoader = require.extensions[".css"];
  require.extensions[".css"] = () => {};
  const sdk = require("@copilotkit/react-core/v2");
  if (previousCssLoader) require.extensions[".css"] = previousCssLoader; else delete require.extensions[".css"];
  dom.window.HTMLCanvasElement.prototype.getContext = () => ({ measureText: () => ({ width: 0 }) });
  const { CopilotKitCore } = require("@copilotkit/core");
  const { HttpAgent } = require("@ag-ui/client");
  const { Subject } = require("rxjs");
  const agent = new HttpAgent({ url: "http://localhost/test", threadId: "order-thread" });
  const streams = [];
  let connects = 0;
  agent.connect = () => { connects++; const stream = new Subject(); streams.push(stream); return stream; };
  const copilotkit = new CopilotKitCore({ agents__unsafe_dev_only: { test: agent } });
  const timers = new Map();
  let timerId = 0;
  dom.window.setInterval = (callback, delay) => { timers.set(++timerId, { callback, delay }); return timerId; };
  dom.window.clearInterval = (id) => timers.delete(id);
  const useAgent = () => {
    const [, update] = React.useReducer((v) => v + 1, 0);
    React.useEffect(() => {
      const subscription = agent.subscribe({ onRunInitialized: update, onRunFinalized: update, onMessagesChanged: update });
      return () => subscription.unsubscribe();
    }, []);
    return { agent, isReady: true };
  };
  function Chat({ chatView: View }) {
    const [value, setValue] = React.useState("");
    return React.createElement(View, { inputValue: value, onInputChange: setValue });
  }
  Chat.View = function ChatView({ input: Input, inputValue, onInputChange, onSubmitMessage }) {
    return React.createElement(Input, { value: inputValue, onChange: onInputChange, onSubmitMessage });
  };
  const stubs = {
    "@copilotkit/react-core/v2": { ...sdk, CopilotChat: Chat, useAgent,
      useCopilotKit: () => ({ copilotkit }), useRenderTool: () => {} },
    "@/lib/http": { apiFetch: async () => ({}) },
    "@/lib/chat-sessions": { getChatSessionMessages: async () => ({ messages: [], nextBeforeSeq: null, hasMore: false }) },
    "@/lib/resource-upload": {},
    "@/components/agui-cards": { ResourceReferenceContext: React.createContext(null) },
    "@/lib/agui-messages": {},
    "./agui-chat-thread.module.css": { default: {} },
    "./chat-attachment-picker": {},
    "@/lib/reference-images": {},
    "./order/order-card": { OrderReferenceContext: React.createContext(null) },
    "./order/order-picker": {},
  };
  const source = readFileSync(new URL("../components/agui-chat-thread.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX,
    module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText;
  const product = { exports: {} };
  new Function("require", "module", "exports", compiled)((id) => stubs[id] ?? require(id), product, product.exports);
  let changes = 0;
  const root = createRoot(dom.window.document.getElementById("root"));
  try {
    await React.act(async () => root.render(React.createElement(product.exports.AguiChatThread,
      { threadId: "order-thread", orderAssistant: true, onChanged: () => { changes++; } })));
    const input = dom.window.document.querySelector("textarea");
    assert.ok(input);
    input.focus();
    await React.act(async () => {
      Object.getOwnPropertyDescriptor(dom.window.HTMLTextAreaElement.prototype, "value").set.call(input, "订单还没输入完");
      input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
    });
    assert.equal(input.disabled, false);
    await React.act(async () => {
      for (let tick = 0; tick < 3; tick++) {
        for (const { callback } of [...timers.values()]) callback();
        await Promise.resolve();
      }
    });
    assert.equal(input.disabled, false, "background polling must not disable the composer while typing");
    assert.equal(dom.window.document.querySelector("textarea"), input, "composer must not remount");
    assert.equal(input.value, "订单还没输入完");
    assert.equal(dom.window.document.activeElement, input);
    assert.equal(connects, 0, "idle polling must not reconnect the chat transport");

    // A real order publication still hydrates the SDK, and duplicate notifications
    // during that connection must not start another replay.
    await React.act(async () => dom.window.dispatchEvent(new dom.window.Event("order:changed")));
    assert.equal(connects, 1);
    assert.equal(changes, 1);
    await React.act(async () => dom.window.dispatchEvent(new dom.window.Event("order:changed")));
    assert.equal(connects, 1);
    await React.act(async () => streams[0].complete());
    assert.equal(input.disabled, false);
    assert.equal(input.value, "订单还没输入完");
    assert.equal(dom.window.document.querySelector("textarea"), input);

    await React.act(async () => [...dom.window.document.querySelectorAll("button")]
      .find((button) => button.textContent === "恢复连接").click());
    assert.equal(connects, 2, "manual recovery remains available");
  } finally {
    await React.act(async () => { streams.forEach((stream) => stream.complete()); await agent.detachActiveRun(); root.unmount(); });
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
  }
});

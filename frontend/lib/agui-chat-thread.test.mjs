import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import ts from "typescript";
import { JSDOM } from "jsdom";
import * as referenceImages from "./reference-images.ts";
import * as aguiMessages from "./agui-messages.ts";
import * as mediaUpdates from "./agui-media-updates.ts";

const require = createRequire(import.meta.url);

// Render the product component with its real SDK input and connection lifecycle.
// Only application data and the SDK's provider-bound hooks/view are replaced.
test("idle order refresh keeps the draft editable and image references synchronized", async () => {
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
  const cardSource = readFileSync(new URL("../components/agui-cards.tsx", import.meta.url), "utf8");
  const cardCode = ts.transpileModule(cardSource, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX,
    module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText;
  const cards = { exports: {} };
  // The real resource card and context run here; unrelated business-card imports are unused.
  new Function("require", "module", "exports", cardCode)((id) => id.startsWith("@/") ? {} : require(id), cards, cards.exports);
  const image = { id: "image-1", type: "IMAGE", role: "OUTPUT", viewUrl: "/image", downloadUrl: "/download",
    fileName: "image.png", mimeType: "image/png", fileSize: 23 };
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
    return React.createElement(React.Fragment, null,
      React.createElement(cards.exports.ResourceCard, { content: { message: { resources: [image] } } }),
      React.createElement(Input, { value: inputValue, onChange: onInputChange, onSubmitMessage }));
  };
  let messageReads = 0;
  let mediaResult;
  let historyReads = 0;
  let writes = 0;
  agent.subscribe({ onMessagesChanged: () => { writes++; } });
  const stubs = {
    "@copilotkit/react-core/v2": { ...sdk, CopilotChat: Chat, useAgent,
      useCopilotKit: () => ({ copilotkit }), useRenderTool: () => {} },
    "@/lib/http": { apiFetch: async () => ({}) },
    "@/lib/chat-sessions": {
      getChatSessionMessages: async () => { historyReads++; return { messages: [], nextBeforeSeq: null, hasMore: false }; },
      getChatSessionMessage: async () => { messageReads++; return mediaResult; },
    },
    "@/lib/resource-upload": {},
    "@/components/agui-cards": cards.exports,
    "@/components/agui-user-message": { ReferencedUserMessage: sdk.CopilotChatUserMessage,
      UserMessageEditContext: React.createContext(null) },
    "@/lib/agui-messages": aguiMessages,
    "@/lib/agui-media-updates": mediaUpdates,
    "./agui-chat-thread.module.css": { default: {} },
    "./chat-attachment-picker": {},
    "@/lib/reference-images": referenceImages,
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
    const reference = dom.window.document.querySelector('button[aria-pressed]');
    const download = dom.window.document.querySelector('a[download]');
    assert.equal(download.textContent.trim(), "", "Download is an icon without a visible filename");
    assert.ok(download.querySelector("svg"));
    assert.equal(download.getAttribute("href"), "/download");
    await React.act(async () => reference.click());
    assert.equal(reference.textContent.trim(), "已引用");
    assert.equal(reference.getAttribute("aria-pressed"), "true");
    assert.match(dom.window.document.querySelector('[aria-label="待发送附件"]').textContent, /引用图片/);
    assert.equal(dom.window.document.activeElement, input, "Selecting an image focuses the draft");
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
    assert.equal(reference.getAttribute("aria-pressed"), "true", "Background updates preserve the image reference");
    await React.act(async () => dom.window.document.querySelector('button[aria-label="移除 image.png"]').click());
    assert.equal(reference.getAttribute("aria-pressed"), "false", "Removing the preview clears the source badge");
    await React.act(async () => reference.click());
    await React.act(async () => reference.click());
    assert.equal(dom.window.document.querySelector('[aria-label="待发送附件"]'), null, "Clicking the selected source cancels the reference");

    // Reproduce an async image waiting in the current chat while the user is composing.
    const pendingImage = { id: "pending-image", role: "assistant", messageType: "IMAGE", content: "图片生成中",
      payload: { status: "PENDING" }, resources: [], createdAt: "2026-10-27T00:00:00" };
    input.setSelectionRange(2, 4);
    input.dispatchEvent(new dom.window.CompositionEvent("compositionstart", { data: "正在输入", bubbles: true }));
    mediaResult = pendingImage;
    await React.act(async () => agent.addMessages(aguiMessages.productHistoryMessages([pendingImage])));
    const writesBeforePoll = writes;
    const changesBeforePoll = changes;
    await React.act(async () => {
      for (const { callback } of [...timers.values()]) callback();
      await Promise.resolve();
    });
    assert.equal(connects, 0, "pending image polling must update its card without reconnecting the chat");
    assert.equal(input.disabled, false, "pending image polling must not interrupt typing or composition");
    assert.equal(messageReads, 1, "only the pending media message is requested");
    assert.equal(writes, writesBeforePoll, "an unchanged result must not notify or rerender SDK messages");
    assert.equal(changes, changesBeforePoll, "polling must not refresh the session metadata");
    assert.equal(input.value, "订单还没输入完");
    assert.equal(dom.window.document.activeElement, input);
    assert.equal(dom.window.document.querySelector("textarea"), input);
    mediaResult = { ...pendingImage, content: "白猫", payload: { status: "READY" }, resources: [image] };
    await React.act(async () => {
      for (const { callback } of [...timers.values()]) callback();
      await Promise.resolve();
    });
    assert.equal(agent.messages.find((message) => message.id === "resource-pending-image").content.message.payload.status, "READY");
    assert.equal(agent.isRunning, false);
    assert.equal(input.disabled, false);
    assert.equal(input.value, "订单还没输入完");
    assert.equal(input.selectionStart, 2);
    assert.equal(input.selectionEnd, 4);
    assert.equal(dom.window.document.querySelector("textarea"), input);
    input.dispatchEvent(new dom.window.CompositionEvent("compositionend", { data: "正在输入", bubbles: true }));
    assert.equal(timers.size, 0, "terminal image state removes the polling timer");
    const readsAfterCompletion = messageReads;
    await React.act(async () => {
      for (const { callback } of [...timers.values()]) callback();
      dom.window.document.dispatchEvent(new dom.window.Event("visibilitychange"));
    });
    assert.equal(messageReads, readsAfterCompletion, "completed chats do not poll or refresh on visibility changes");
    assert.equal(connects, 0);
    assert.equal(historyReads, 1, "media polling never reloads the whole history");
    await React.act(async () => agent.setMessages([]));

    // An order publication checks for new messages without replaying a run or disabling the input.
    const writesBeforeOrder = writes;
    await React.act(async () => dom.window.dispatchEvent(new dom.window.Event("order:changed")));
    assert.equal(connects, 0);
    assert.equal(writes, writesBeforeOrder);
    assert.equal(changes, changesBeforePoll);
    assert.equal(input.disabled, false);
    assert.equal(input.value, "订单还没输入完");

    await React.act(async () => [...dom.window.document.querySelectorAll("button")]
      .find((button) => button.textContent === "恢复连接").click());
    assert.equal(connects, 1, "manual recovery remains available");
    await React.act(async () => streams[0].complete());
    let forwarded;
    copilotkit.runAgent = async ({ forwardedProps }) => { forwarded = forwardedProps; };
    await React.act(async () => reference.click());
    await React.act(async () => input.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true })));
    assert.deepEqual(forwarded.resources, [{ resourceId: "image-1", role: "REFERENCE", source: "HISTORY" }]);
    const optimistic = aguiMessages.orderProductMessages(agent.messages);
    assert.equal(optimistic.length, 1, "Sending renders one user message, without a separate reference card");
    assert.deepEqual(optimistic[0].content.map((part) => part.type), ["text", "image"]);
    assert.equal(optimistic[0].content[0].text, "订单还没输入完");
    assert.equal(optimistic[0].content[1].source.value, "/image");
    const savedMessage = { id: "saved-user", role: "user", messageType: "USER", content: "订单还没输入完",
      resources: [{ ...image, id: "bound-reference", role: "REFERENCE" }], createdAt: "2026-10-26T00:00:00" };
    await React.act(async () => agent.setMessages([
      ...agent.messages.filter((message) => message.role === "activity"), ...aguiMessages.productHistoryMessages([savedMessage]),
    ]));
    const restored = aguiMessages.orderProductMessages(agent.messages);
    assert.equal(restored.length, 1, "An authoritative snapshot hides the replaced optimistic reference card");
    assert.equal(restored[0].id, "saved-user");
    assert.equal(restored[0].content[1].source.value, "/image");
  } finally {
    await React.act(async () => { streams.forEach((stream) => stream.complete()); await agent.detachActiveRun(); root.unmount(); });
    dom.window.close();
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key];
    }
  }
});

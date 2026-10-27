import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const previousCssLoader = require.extensions[".css"];
require.extensions[".css"] = () => {};
const { CopilotKit } = require("@copilotkit/react-core/v2");
if (previousCssLoader) require.extensions[".css"] = previousCssLoader; else delete require.extensions[".css"];
const { HttpAgent } = require("@ag-ui/client");
const code = ts.transpileModule(readFileSync(new URL("../components/agui-user-message.tsx", import.meta.url), "utf8"), {
  compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
}).outputText;
const component = { exports: {} };
new Function("require", "module", "exports", code)((id) => id.endsWith(".module.css") ? {} : require(id), component, component.exports);

test("the real SDK user message places a reference thumbnail below the text and opens the original", () => {
  const message = { id: "user", role: "user", content: [
    { type: "text", text: "以此图生成一个15秒的视频" },
    { type: "image", source: { type: "url", value: "/original" }, metadata: { hAgentReference: true, filename: "ref.png" } },
  ] };
  const html = renderToStaticMarkup(React.createElement(CopilotKit, {
    agents__unsafe_dev_only: { default: new HttpAgent({ url: "http://localhost/fixture" }) }, enableInspector: false,
  }, React.createElement(component.exports.ReferencedUserMessage, { message })));
  const dom = new JSDOM(html);
  try {
    const user = dom.window.document.querySelector('[data-testid="copilot-user-message"]');
    const text = user.querySelector('[class~="cpk:prose"]');
    const image = user.querySelector("img");
    assert.ok(text.compareDocumentPosition(image) & dom.window.Node.DOCUMENT_POSITION_FOLLOWING,
      "The thumbnail must follow the text inside the same user message");
    assert.equal(image.closest("a").getAttribute("href"), "/original");
    assert.equal(image.closest("a").getAttribute("target"), "_blank");
    assert.equal(user.querySelectorAll("img").length, 1);
    assert.equal(user.querySelector(".agui-card"), null);
  } finally { dom.window.close(); }
});

test("mixed video and audio references stay inside compact links below the sent text", () => {
  const message = { id: "mixed", role: "user", content: [
    { type: "text", text: "参考视频和配乐生成15秒的视频" },
    { type: "video", source: { type: "url", value: "/video" }, metadata: { hAgentReference: true, resourceId: "v" } },
    { type: "audio", source: { type: "url", value: "/audio" }, metadata: { hAgentReference: true, resourceId: "a" } },
  ] };
  const html = renderToStaticMarkup(React.createElement(CopilotKit, {
    agents__unsafe_dev_only: { default: new HttpAgent({ url: "http://localhost/fixture" }) }, enableInspector: false,
  }, React.createElement(component.exports.ReferencedUserMessage, { message })));
  const dom = new JSDOM(html);
  try {
    const refs = dom.window.document.querySelector('[aria-label="此消息引用的素材"]');
    assert.equal(refs.querySelectorAll("a").length, 2);
    assert.equal(dom.window.document.querySelectorAll("video").length, 1);
    assert.equal(dom.window.document.querySelectorAll("audio").length, 0);
    assert.equal(refs.querySelector("video").hasAttribute("controls"), false);
    assert.equal(refs.querySelector("video").hasAttribute("autoplay"), false);
    assert.equal(refs.querySelectorAll("a[target='_blank']").length, 2);
  } finally { dom.window.close(); }
});

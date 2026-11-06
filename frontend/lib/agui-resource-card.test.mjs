import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import ts from "typescript";
import {
  orderProductMessages,
  productHistoryMessages,
} from "./agui-messages.ts";

const require = createRequire(import.meta.url);
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
const code = ts.transpileModule(
  readFileSync(
    new URL("../components/agui-cards.tsx", import.meta.url),
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
const cardsModule = { exports: {} };
// Render the real resource card; unrelated business cards are outside this test's scope.
new Function("require", "module", "exports", code)(
  (id) =>
    id.startsWith("@/") || id === "@copilotkit/react-core/v2"
      ? {}
      : require(id),
  cardsModule,
  cardsModule.exports,
);

const resource = {
  id: "pdf",
  type: "FILE",
  role: "ATTACHMENT",
  fileName: "项目说明.pdf",
  mimeType: "application/pdf",
  fileSize: 1572864,
  viewUrl: "/resources/pdf/view",
  downloadUrl: "/resources/pdf/download",
};

for (const role of ["user", "assistant"]) {
  test(`${role} PDF cards show filename, format and size after message projection`, () => {
    const message = {
      id: "message",
      role,
      messageType: role === "user" ? "USER" : "FILE",
      content: "查看附件",
      resources: [
        { ...resource, role: role === "user" ? "ATTACHMENT" : "GENERATED" },
      ],
    };
    const card = orderProductMessages(productHistoryMessages([message])).find(
      (item) => item.activityType === "h-agent.resource",
    );
    const dom = new JSDOM(
      renderToStaticMarkup(
        React.createElement(cardsModule.exports.ResourceCard, {
          content: card.content,
        }),
      ),
    );
    try {
      const document = dom.window.document;
      assert.match(document.body.textContent, /项目说明\.pdf/);
      assert.match(document.body.textContent, /PDF/);
      assert.match(document.body.textContent, /1\.5 MB/);
      assert.equal(
        document.querySelector("a[download]").getAttribute("href"),
        resource.downloadUrl,
      );
      assert.equal(
        document.querySelector("a[download]").getAttribute("download"),
        resource.fileName,
      );
    } finally {
      dom.window.close();
    }
  });
}

test("other attachments show their type and do not invent an unknown size", () => {
  const dom = new JSDOM(
    renderToStaticMarkup(
      React.createElement(cardsModule.exports.ResourceCard, {
        content: {
          message: {
            resources: [
              {
                ...resource,
                fileName: "会议记录.docx",
                mimeType: "application/octet-stream",
                fileSize: null,
              },
              {
                ...resource,
                id: "empty",
                fileName: "空文件.txt",
                mimeType: "text/plain",
                fileSize: 0,
              },
            ],
          },
        },
      }),
    ),
  );
  try {
    assert.match(dom.window.document.body.textContent, /会议记录\.docx/);
    assert.match(dom.window.document.body.textContent, /DOCX/);
    assert.equal(dom.window.document.body.textContent.match(/0 B/g)?.length, 1);
    assert.doesNotMatch(
      dom.window.document.body.textContent,
      /null|NaN|undefined/,
    );
  } finally {
    dom.window.close();
  }
});

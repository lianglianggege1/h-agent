import assert from "node:assert/strict";
import { test } from "node:test";
import { previewKind, readPreviewBytes, previewLimit } from "./file-preview.ts";
import { orderProductMessages, productHistoryMessages } from "./agui-messages.ts";

const resource = { id: "file", fileName: "notes.txt", mimeType: "text/plain", fileSize: null, viewUrl: "/content", downloadUrl: "/download" };

test("preview dispatch handles legacy MIME metadata without treating Office ZIPs or SVG as executable content", () => {
  for (const [fileName, mimeType, expected] of [
    ["report.PDF", "application/octet-stream", "pdf"],
    ["report.docx", "application/zip", "docx"],
    ["notes.MD", "text/plain", "markdown"],
    ["app.tsx", "application/octet-stream", "text"],
    ["vector.svg", "image/svg+xml", "text"],
    ["sheet.xlsx", "text/plain", "unsupported"],
    ["slides.pptx", "application/zip", "unsupported"],
    ["photo.png", "image/png", "image"],
    ["movie.mp4", "video/mp4", "video"],
    ["song.mp3", "audio/mpeg", "audio"],
  ]) assert.equal(previewKind({ fileName, mimeType }), expected, fileName);
});

test("mixed historical attachments retain one complete preview group through both message render paths", () => {
  const image = { ...resource, id: "image", fileName: "ref.png", mimeType: "image/png", role: "REFERENCE" };
  const pdf = { ...resource, id: "pdf", fileName: "brief.pdf", mimeType: "application/pdf", role: "ATTACHMENT" };
  const projected = orderProductMessages(productHistoryMessages([{ id: "old", role: "user", messageType: "USER", content: "参考资料", resources: [image, pdf] }]));
  assert.equal(projected[0].content[1].metadata.resource.downloadUrl, image.downloadUrl);
  assert.deepEqual(projected[0].content[1].metadata.previewResources.map(item => item.id), ["image", "pdf"]);
  const card = projected.find(item => item.activityType === "h-agent.resource");
  assert.deepEqual(card.content.message.resources.map(item => item.id), ["pdf"]);
  assert.deepEqual(card.content.previewResources.map(item => item.id), ["image", "pdf"]);
});

test("preview rejects declared oversize files before fetching", async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = () => { throw new Error("must not fetch"); };
  try { await assert.rejects(readPreviewBytes({ ...resource, fileSize: previewLimit("text") + 1 }, "text", new AbortController().signal), /预览上限/); }
  finally { globalThis.fetch = previous; }
});

test("unknown-length oversized responses are bounded and canceled during reading", async () => {
  const previous = globalThis.fetch;
  let canceled = false;
  globalThis.fetch = async () => new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(previewLimit("text"))); controller.enqueue(new Uint8Array(1)); },
    cancel() { canceled = true; },
  }));
  try {
    await assert.rejects(readPreviewBytes(resource, "text", new AbortController().signal), /预览上限/);
    assert.equal(canceled, true);
  } finally { globalThis.fetch = previous; }
});

test("file errors remain actionable and successful reads carry authentication and cancellation", async () => {
  const previous = globalThis.fetch;
  const controller = new AbortController();
  try {
    for (const [status, message] of [[401, /登录/], [403, /权限/], [404, /已被清理/], [500, /重试/]]) {
      globalThis.fetch = async () => new Response("", { status });
      await assert.rejects(readPreviewBytes(resource, "text", controller.signal), message);
    }
    globalThis.fetch = async (url, init) => {
      assert.equal(url, "/content");
      assert.equal(init.credentials, "include");
      assert.equal(init.signal, controller.signal);
      return new Response("中文附件内容");
    };
    assert.equal(new TextDecoder().decode(await readPreviewBytes(resource, "text", controller.signal)), "中文附件内容");
  } finally { globalThis.fetch = previous; }
});

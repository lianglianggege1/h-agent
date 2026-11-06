import assert from "node:assert/strict";
import { test } from "node:test";
import { mergeProductMessageUpdates } from "./agui-media-updates.ts";

test("background document delivery creates one downloadable card across repeated refreshes", () => {
  const delivered = {
    id: "doc-message",
    role: "assistant",
    messageType: "FILE",
    content: "已生成文件：报告.docx",
    createdAt: "2026-11-05T00:00:00",
    resources: [
      {
        id: "document",
        type: "FILE",
        role: "GENERATED",
        mimeType:
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        fileName: "报告.docx",
        downloadUrl: "/api/chat/resources/document/download",
        viewUrl: "/api/chat/resources/document/content",
      },
    ],
  };
  const first = mergeProductMessageUpdates([], [delivered]);
  const repeated = mergeProductMessageUpdates(first, [delivered]);
  assert.equal(repeated.length, 2);
  assert.equal(
    repeated.filter((message) => message.role === "activity").length,
    1,
  );
  assert.equal(
    repeated.find((message) => message.role === "activity").content.message
      .resources[0].downloadUrl,
    "/api/chat/resources/document/download",
  );
  assert.equal(repeated, first);
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { historyReferenceImages, selectHistoryReference } from "./reference-images.ts";

const image = { id: "image-1", type: "IMAGE", role: "OUTPUT", viewUrl: "/fresh-view", downloadUrl: "/download", fileName: "image.png", mimeType: "image/png", fileSize: 23 };
const resourceMessage = (id, resources, messageType = "IMAGE") => ({
  id, role: "activity", activityType: "h-agent.resource", content: { message: { id, messageType, resources } },
});

test("history picker exposes generated images from SDK resource cards without duplicates", () => {
  const messages = [
    { id: "text", role: "assistant", content: "answer" },
    resourceMessage("old", [{ ...image, viewUrl: "/old-view" }]),
    resourceMessage("new", [image, { ...image, id: "audio", type: "AUDIO", mimeType: "audio/mpeg" }]),
    resourceMessage("uploaded", [{ ...image, id: "upload" }], "USER"),
  ];
  assert.deepEqual(historyReferenceImages(messages), [image]);
});

test("selecting history images preserves uploads and forwards the resource identity as a reference", () => {
  const upload = { resourceId: "upload", role: "ATTACHMENT", source: "UPLOAD" };
  const selected = selectHistoryReference([upload], image);
  assert.deepEqual(selected[1], {
    resourceId: "image-1", type: "IMAGE", role: "REFERENCE", source: "HISTORY",
    viewUrl: "/fresh-view", downloadUrl: "/download", fileName: "image.png", mimeType: "image/png", fileSize: 23,
  });
  assert.equal(selectHistoryReference(selected, image).length, 2);
  const multiple = selectHistoryReference(selected, { ...image, id: "image-2" });
  assert.deepEqual(multiple.map(({ resourceId, role, source }) => ({ resourceId, role, source })), [
    { resourceId: "upload", role: "ATTACHMENT", source: "UPLOAD" },
    { resourceId: "image-1", role: "REFERENCE", source: "HISTORY" },
    { resourceId: "image-2", role: "REFERENCE", source: "HISTORY" },
  ]);
});

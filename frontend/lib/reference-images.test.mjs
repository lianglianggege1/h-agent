import assert from "node:assert/strict";
import { test } from "node:test";
import {
  historyReferenceImages,
  historyReferenceResources,
  selectHistoryReference,
} from "./reference-images.ts";

const image = {
  id: "image-1",
  type: "IMAGE",
  role: "OUTPUT",
  viewUrl: "/fresh-view",
  downloadUrl: "/download",
  fileName: "image.png",
  mimeType: "image/png",
  fileSize: 23,
};
const resourceMessage = (id, resources, messageType = "IMAGE") => ({
  id,
  role: "activity",
  activityType: "h-agent.resource",
  content: { message: { id, messageType, resources } },
});

test("history picker exposes generated images from SDK resource cards without duplicates", () => {
  const messages = [
    { id: "text", role: "assistant", content: "answer" },
    resourceMessage("old", [{ ...image, viewUrl: "/old-view" }]),
    resourceMessage("new", [
      image,
      { ...image, id: "audio", type: "AUDIO", mimeType: "audio/mpeg" },
    ]),
    resourceMessage("uploaded", [{ ...image, id: "upload" }], "USER"),
  ];
  assert.deepEqual(historyReferenceImages(messages), [image]);
});

test("selecting history images preserves uploads and forwards the resource identity as a reference", () => {
  const upload = { resourceId: "upload", role: "ATTACHMENT", source: "UPLOAD" };
  const selected = selectHistoryReference([upload], image);
  assert.deepEqual(selected[1], {
    resourceId: "image-1",
    type: "IMAGE",
    role: "REFERENCE",
    source: "HISTORY",
    viewUrl: "/fresh-view",
    downloadUrl: "/download",
    fileName: "image.png",
    mimeType: "image/png",
    fileSize: 23,
  });
  assert.equal(selectHistoryReference(selected, image).length, 2);
  const multiple = selectHistoryReference(selected, {
    ...image,
    id: "image-2",
  });
  assert.deepEqual(
    multiple.map(({ resourceId, role, source }) => ({
      resourceId,
      role,
      source,
    })),
    [
      { resourceId: "upload", role: "ATTACHMENT", source: "UPLOAD" },
      { resourceId: "image-1", role: "REFERENCE", source: "HISTORY" },
      { resourceId: "image-2", role: "REFERENCE", source: "HISTORY" },
    ],
  );
});

test("multimodal history references include owned images, videos and audio", () => {
  const video = { ...image, id: "video", type: "VIDEO", mimeType: "video/mp4" };
  const audio = {
    ...image,
    id: "audio",
    type: "AUDIO",
    mimeType: "audio/mpeg",
  };
  const media = historyReferenceResources([
    resourceMessage("image", [image]),
    resourceMessage("clip", [video], "VIDEO"),
    resourceMessage("uploaded-audio", [audio], "USER"),
  ]);
  assert.deepEqual(
    media.map((item) => item.id),
    ["image-1", "video", "audio"],
  );
  assert.equal(selectHistoryReference([], video)[0].role, "REFERENCE");
  assert.equal(selectHistoryReference([], audio)[0].source, "HISTORY");
});

test("history references include uploaded and generated documents and deduplicate by resource ID", () => {
  const pdf = {
    ...image,
    id: "pdf",
    type: "FILE",
    role: "ATTACHMENT",
    fileName: "资料.pdf",
    mimeType: "application/pdf",
  };
  const docx = {
    ...pdf,
    id: "docx",
    fileName: "输出.docx",
    mimeType:
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  };
  const messages = [
    resourceMessage("upload", [pdf], "USER"),
    resourceMessage("output", [docx], "FILE"),
    resourceMessage("again", [{ ...pdf, downloadUrl: "/fresh-pdf" }], "USER"),
  ];
  const resources = historyReferenceResources(messages);
  assert.deepEqual(
    resources.map((item) => item.id),
    ["pdf", "docx"],
  );
  assert.equal(resources[0].downloadUrl, "/fresh-pdf");
  const selected = selectHistoryReference([], resources[0]);
  assert.deepEqual(
    selected.map(({ resourceId, role, source }) => ({
      resourceId,
      role,
      source,
    })),
    [{ resourceId: "pdf", role: "ATTACHMENT", source: "HISTORY" }],
  );
  assert.equal(selectHistoryReference(selected, resources[0]).length, 1);
});

test("history file selection preserves unknown sizes instead of inventing empty files", () => {
  const selected = selectHistoryReference([], {
    ...image,
    id: "pdf",
    type: "FILE",
    mimeType: "application/pdf",
    fileSize: null,
  });
  assert.equal(selected[0].fileSize, null);
  assert.equal(selected[0].role, "ATTACHMENT");
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { apiFetch, apiStream } from "./http.ts";

test("apiFetch throws stable message when response is not JSON", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("Bad Gateway", { status: 502 });

  try {
    await assert.rejects(() => apiFetch("/api/auth/login"), {
      message: "请求失败",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream throws and notifies handler for error events", async () => {
  const originalFetch = globalThis.fetch;
  const errorMessage = "请求失败，请稍后重试";
  let receivedErrorMessage;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              `${JSON.stringify({ type: "error", content: errorMessage })}\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200 },
    );

  try {
    await assert.rejects(
      () =>
        apiStream(
          "/api/chat/stream",
          { method: "POST" },
          {
            onChunk() {},
            onError(message) {
              receivedErrorMessage = message;
            },
          },
        ),
      { message: errorMessage },
    );
    assert.equal(receivedErrorMessage, errorMessage);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches blocked events without throwing", async () => {
  const originalFetch = globalThis.fetch;
  const blockedMessage = "系统提醒您：请勿使用暴力";
  const chunks = [];
  let receivedBlockedMessage;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              `${JSON.stringify({ type: "blocked", content: blockedMessage })}\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200 },
    );

  try {
    await assert.doesNotReject(() =>
      apiStream(
        "/api/chat/stream",
        { method: "POST" },
        {
          onChunk(value) {
            chunks.push(value);
          },
          onBlocked(message) {
            receivedBlockedMessage = message;
          },
        },
      ),
    );
    assert.equal(receivedBlockedMessage, blockedMessage);
    assert.deepEqual(chunks, []);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

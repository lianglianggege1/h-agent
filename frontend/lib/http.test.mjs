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
              "event: error\n" +
                `data: ${JSON.stringify({ type: "error", content: errorMessage })}\n\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
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
              "event: blocked\n" +
                `data: ${JSON.stringify({ type: "blocked", content: blockedMessage })}\n\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
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

test("apiStream dispatches chunk and done events from sse blocks", async () => {
  const originalFetch = globalThis.fetch;
  const chunks = [];
  let doneCalled = false;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: chunk\n" +
                'data: {"type":"chunk","content":"he"}\n\n' +
                "event: chunk\n" +
                'data: {"type":"chunk","content":"llo"}\n\n' +
                "event: done\n" +
                'data: {"type":"done","content":""}\n\n',
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk(value) {
        chunks.push(value);
      },
      onDone() {
        doneCalled = true;
      },
    });
    assert.deepEqual(chunks, ["he", "llo"]);
    assert.equal(doneCalled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches events from CRLF-delimited sse blocks", async () => {
  const originalFetch = globalThis.fetch;
  const chunks = [];
  let doneCalled = false;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: chunk\r\n" +
                'data: {"type":"chunk","content":"he"}\r\n\r\n' +
                "event: chunk\r\n" +
                'data: {"type":"chunk","content":"llo"}\r\n\r\n' +
                "event: done\r\n" +
                'data: {"type":"done","content":""}\r\n\r\n',
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk(value) {
        chunks.push(value);
      },
      onDone() {
        doneCalled = true;
      },
    });
    assert.deepEqual(chunks, ["he", "llo"]);
    assert.equal(doneCalled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream handles final sse block without trailing blank line", async () => {
  const originalFetch = globalThis.fetch;
  const chunks = [];
  let doneCalled = false;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: chunk\n" +
                'data: {"type":"chunk","content":"hello"}\n\n' +
                "event: done\n" +
                'data: {"type":"done","content":""}',
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk(value) {
        chunks.push(value);
      },
      onDone() {
        doneCalled = true;
      },
    });
    assert.deepEqual(chunks, ["hello"]);
    assert.equal(doneCalled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

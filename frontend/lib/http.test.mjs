import assert from "node:assert/strict";
import { test } from "node:test";
import { apiFetch, apiFormFetch, apiStream } from "./http.ts";

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

test("apiFormFetch sends FormData without forcing JSON content type", async () => {
  const originalFetch = globalThis.fetch;
  const form = new FormData();
  form.append("promptId", "7");
  form.append("file", new Blob(["hello"], { type: "text/plain" }), "note.txt");
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: 42 }), { status: 200 });
  };

  try {
    const result = await apiFormFetch("/api/knowledge/documents/upload", form);
    const headers = capturedRequest.init.headers;

    assert.equal(result, 42);
    assert.equal(capturedRequest.path, "/api/knowledge/documents/upload");
    assert.equal(capturedRequest.init.method, "POST");
    assert.equal(capturedRequest.init.body, form);
    assert.equal(capturedRequest.init.credentials, "include");
    assert.equal(headers.has("Content-Type"), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiFormFetch strips caller-provided content type for FormData", async () => {
  const originalFetch = globalThis.fetch;
  const form = new FormData();
  form.append("promptId", "7");
  let capturedHeaders;

  globalThis.fetch = async (_path, init) => {
    capturedHeaders = init.headers;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: 42 }), { status: 200 });
  };

  try {
    await apiFormFetch("/api/knowledge/documents/upload", form, {
      headers: { "Content-Type": "application/json" },
    });

    assert.equal(capturedHeaders.has("Content-Type"), false);
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

test("apiStream dispatches reasoning events without affecting chunk flow", async () => {
  const originalFetch = globalThis.fetch;
  const events = [];

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: reasoning\n" +
                'data: {"type":"reasoning","content":"先拆约束"}\n\n' +
                "event: chunk\n" +
                'data: {"type":"chunk","content":"最终答案"}\n\n' +
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
      onReasoning(value) {
        events.push(["reasoning", value]);
      },
      onChunk(value) {
        events.push(["chunk", value]);
      },
      onDone() {
        events.push(["done", ""]);
      },
    });
    assert.deepEqual(events, [
      ["reasoning", "先拆约束"],
      ["chunk", "最终答案"],
      ["done", ""],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches image events with message payload", async () => {
  const originalFetch = globalThis.fetch;
  const events = [];
  const imageMessage = {
    id: "501",
    role: "assistant",
    messageType: "IMAGE",
    content: "一只白猫",
    resources: [
      {
        id: "resource-1",
        viewUrl: "/api/chat/resources/resource-1/content",
        downloadUrl: "/api/chat/resources/resource-1/download",
        fileName: "generated.png",
        mimeType: "image/png",
        width: 1024,
        height: 1024,
      },
    ],
    createdAt: "2026-06-03T20:00:00",
  };

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: image\n" +
                `data: ${JSON.stringify({ type: "image", content: "", message: imageMessage })}\n\n` +
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
      onChunk() {},
      onImage(message) {
        events.push(["image", message]);
      },
      onDone() {
        events.push(["done", ""]);
      },
    });
    assert.deepEqual(events, [
      ["image", imageMessage],
      ["done", ""],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches resource events with message payload", async () => {
  const originalFetch = globalThis.fetch;
  const events = [];
  const fileMessage = {
    id: "601",
    role: "assistant",
    messageType: "FILE",
    content: "已发送文件：客户方案.pptx",
    resources: [
      {
        id: "resource-2",
        type: "FILE",
        viewUrl: "/api/chat/resources/resource-2/content",
        downloadUrl: "/api/chat/resources/resource-2/download",
        fileName: "客户方案.pptx",
        mimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      },
    ],
    createdAt: "2026-07-07T20:00:00",
  };

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: resource\n" +
                `data: ${JSON.stringify({ type: "resource", content: "", message: fileMessage })}\n\n` +
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
      onChunk() {},
      onImage(message) {
        events.push(["resource", message]);
      },
      onDone() {
        events.push(["done", ""]);
      },
    });
    assert.deepEqual(events, [
      ["resource", fileMessage],
      ["done", ""],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches user_message and done message payloads", async () => {
  const originalFetch = globalThis.fetch;
  const events = [];
  const userMessage = {
    id: "601",
    role: "user",
    messageType: "TEXT",
    content: "你好",
    resources: [],
    createdAt: "2026-06-27T10:00:00",
  };
  const assistantMessage = {
    id: "602",
    role: "assistant",
    messageType: "TEXT",
    content: "你好，有什么可以帮你？",
    resources: [],
    createdAt: "2026-06-27T10:00:01",
  };

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: user_message\n" +
                `data: ${JSON.stringify({ type: "user_message", content: "", message: userMessage })}\n\n` +
                "event: done\n" +
                `data: ${JSON.stringify({ type: "done", content: "", message: assistantMessage })}\n\n`,
            ),
          );
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

  try {
    await apiStream("/api/chat/messages/stream", { method: "POST" }, {
      onChunk() {},
      onUserMessage(message) {
        events.push(["user", message]);
      },
      onDone(_content, message) {
        events.push(["done", message]);
      },
    });
    assert.deepEqual(events, [
      ["user", userMessage],
      ["done", assistantMessage],
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("apiStream dispatches agent_step events", async () => {
  const originalFetch = globalThis.fetch;
  const steps = [];

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              "event: agent_step\n" +
                'data: {"type":"agent_step","content":"正在执行：客户信息提取","payload":{"invocationId":"i1","nodeName":"客户信息提取","status":"running"}}\n\n' +
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
      onChunk() {},
      onAgentStep(step) {
        steps.push(step);
      },
    });

    assert.equal(steps[0].nodeName, "客户信息提取");
    assert.equal(steps[0].status, "running");
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

test("apiStream ignores heartbeat comment blocks", async () => {
  const originalFetch = globalThis.fetch;
  const chunks = [];
  let doneCalled = false;

  globalThis.fetch = async () =>
    new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              ": keepalive\n\n" +
                "event: chunk\n" +
                'data: {"type":"chunk","content":"hello"}\n\n' +
                ": keepalive\n\n" +
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
    assert.deepEqual(chunks, ["hello"]);
    assert.equal(doneCalled, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

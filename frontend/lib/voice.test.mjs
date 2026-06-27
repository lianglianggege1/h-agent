import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { test } from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === "./http" && context.parentURL?.endsWith("/voice.ts")) {
      return nextResolve("./http.ts", context);
    }

    return nextResolve(specifier, context);
  },
});

const {
  cancelCallTurn,
  finalizeCallTurn,
  messageTts,
  previewTts,
  startCallTurn,
  uploadCallTurnChunk,
} = await import("./voice.ts");

test("startCallTurn posts session and agent ids", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: { turnId: "turn-1" } }), {
      status: 200,
    });
  };

  try {
    const result = await startCallTurn("session-1", "agent-1");
    const headers = capturedRequest.init.headers;

    assert.deepEqual(result, { turnId: "turn-1" });
    assert.equal(capturedRequest.path, "/api/voice/call-turns/start");
    assert.equal(capturedRequest.init.method, "POST");
    assert.equal(capturedRequest.init.credentials, "include");
    assert.equal(headers.get("Content-Type"), "application/json");
    assert.deepEqual(JSON.parse(capturedRequest.init.body), {
      sessionId: "session-1",
      agentId: "agent-1",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("previewTts returns audio blob with response content type", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(new Blob(["audio"], { type: "audio/mpeg" }), {
      status: 200,
      headers: { "Content-Type": "audio/mpeg" },
    });
  };

  try {
    const result = await previewTts("session-1", "agent-1", "你好");
    const headers = capturedRequest.init.headers;

    assert.equal(capturedRequest.path, "/api/voice/tts/preview");
    assert.equal(capturedRequest.init.method, "POST");
    assert.equal(capturedRequest.init.credentials, "include");
    assert.equal(headers["Content-Type"], "application/json");
    assert.deepEqual(JSON.parse(capturedRequest.init.body), {
      sessionId: "session-1",
      agentId: "agent-1",
      text: "你好",
    });
    assert.equal(result.type, "audio/mpeg");
    assert.equal(await result.text(), "audio");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("previewTts throws backend api response message on failure", async () => {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () =>
    new Response(JSON.stringify({ code: 4001, message: "TTS 配额不足", data: null }), {
      status: 429,
      headers: { "Content-Type": "application/json" },
    });

  try {
    await assert.rejects(() => previewTts("session-1", "agent-1", "你好"), {
      message: "TTS 配额不足",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("previewTts throws fallback message when failure body is not json", async () => {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () => new Response("upstream unavailable", { status: 502 });

  try {
    await assert.rejects(() => previewTts("session-1", "agent-1", "你好"), {
      message: "语音合成失败",
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("uploadCallTurnChunk posts multipart chunk form", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;
  const chunk = new Blob(["voice"], { type: "audio/webm" });

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: null }), { status: 200 });
  };

  try {
    await uploadCallTurnChunk("turn/1", chunk, 7, "audio/webm");
    const form = capturedRequest.init.body;
    const uploadedChunk = form.get("chunk");

    assert.equal(capturedRequest.path, "/api/voice/call-turns/turn%2F1/chunks");
    assert.equal(capturedRequest.init.method, "POST");
    assert.equal(capturedRequest.init.credentials, "include");
    assert.equal(form instanceof FormData, true);
    assert.equal(uploadedChunk instanceof Blob, true);
    assert.equal(uploadedChunk.name, "chunk-7.webm");
    assert.equal(await uploadedChunk.text(), "voice");
    assert.equal(form.get("sequence"), "7");
    assert.equal(form.get("mimeType"), "audio/webm");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("finalizeCallTurn posts encoded turn id and numeric message id", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(
      JSON.stringify({
        code: 0,
        message: "ok",
        data: {
          resourceId: "resource-1",
          viewUrl: "/api/resources/resource-1/view",
          downloadUrl: "/api/resources/resource-1/download",
          mimeType: "audio/webm",
          durationMs: null,
        },
      }),
      { status: 200 },
    );
  };

  try {
    const result = await finalizeCallTurn({
      turnId: "turn/1",
      sessionId: "session-1",
      agentId: "agent-1",
      messageId: "42",
      transcript: "你好",
    });

    assert.equal(capturedRequest.path, "/api/voice/call-turns/turn%2F1/finalize");
    assert.deepEqual(JSON.parse(capturedRequest.init.body), {
      sessionId: "session-1",
      agentId: "agent-1",
      messageId: 42,
      transcript: "你好",
    });
    assert.equal(result.resourceId, "resource-1");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("finalizeCallTurn rejects invalid message id before request", async () => {
  const originalFetch = globalThis.fetch;
  let called = false;

  globalThis.fetch = async () => {
    called = true;
    throw new Error("should not request");
  };

  try {
    assert.throws(
      () =>
        finalizeCallTurn({
          turnId: "turn-1",
          sessionId: "session-1",
          agentId: "agent-1",
          messageId: "not-a-number",
          transcript: "你好",
        }),
      { message: "消息 ID 无效" },
    );
    assert.equal(called, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("cancelCallTurn posts encoded turn id", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(JSON.stringify({ code: 0, message: "ok", data: null }), { status: 200 });
  };

  try {
    await cancelCallTurn("turn/1");

    assert.equal(capturedRequest.path, "/api/voice/call-turns/turn%2F1/cancel");
    assert.equal(capturedRequest.init.method, "POST");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("messageTts posts numeric message id", async () => {
  const originalFetch = globalThis.fetch;
  let capturedRequest;

  globalThis.fetch = async (path, init) => {
    capturedRequest = { path, init };
    return new Response(
      JSON.stringify({
        code: 0,
        message: "ok",
        data: {
          resourceId: "resource-1",
          viewUrl: "/api/resources/resource-1/view",
          downloadUrl: "/api/resources/resource-1/download",
          mimeType: "audio/mpeg",
          durationMs: 1200,
        },
      }),
      { status: 200 },
    );
  };

  try {
    const result = await messageTts("session-1", "agent-1", "43");

    assert.equal(capturedRequest.path, "/api/voice/tts/message");
    assert.deepEqual(JSON.parse(capturedRequest.init.body), {
      sessionId: "session-1",
      agentId: "agent-1",
      messageId: 43,
    });
    assert.equal(result.resourceId, "resource-1");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("messageTts rejects invalid message id before request", async () => {
  const originalFetch = globalThis.fetch;
  let called = false;

  globalThis.fetch = async () => {
    called = true;
    throw new Error("should not request");
  };

  try {
    assert.throws(() => messageTts("session-1", "agent-1", "NaN"), {
      message: "消息 ID 无效",
    });
    assert.equal(called, false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

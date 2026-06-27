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

const { previewTts, startCallTurn, uploadCallTurnChunk } = await import("./voice.ts");

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

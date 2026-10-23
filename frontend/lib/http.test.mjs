import assert from "node:assert/strict";
import { test } from "node:test";
import { apiFetch, apiFormFetch } from "./http.ts";

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

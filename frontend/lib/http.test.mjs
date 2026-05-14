import assert from "node:assert/strict";
import { test } from "node:test";

async function parseApiResponse(response) {
  try {
    return await response.json();
  } catch {
    throw new Error("请求失败");
  }
}

async function apiFetch(path, init = {}) {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");

  const response = await fetch(path, {
    ...init,
    headers,
  });
  const body = await parseApiResponse(response);

  if (!response.ok || body.code !== 0) {
    throw new Error(body.message || "请求失败");
  }

  return body.data;
}

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

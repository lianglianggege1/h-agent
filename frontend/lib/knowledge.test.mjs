import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { test } from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === "./http" && context.parentURL?.endsWith("/knowledge.ts")) {
      return nextResolve("./http.ts", context);
    }

    return nextResolve(specifier, context);
  },
});

const {
  createManualKnowledge,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  listKnowledgeSegments,
  uploadKnowledgeDocument,
} = await import("./knowledge.ts");

test("listKnowledgeDocuments calls prompt-scoped endpoint", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;

  globalThis.fetch = async (path) => {
    capturedPath = path;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: [] }), { status: 200 });
  };

  try {
    const result = await listKnowledgeDocuments(12);
    assert.deepEqual(result, []);
    assert.equal(capturedPath, "/api/knowledge/documents?promptId=12");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("uploadKnowledgeDocument posts multipart file and promptId", async () => {
  const originalFetch = globalThis.fetch;
  const file = new File(["hello"], "note.txt", { type: "text/plain" });
  let capturedPath;
  let capturedInit;

  globalThis.fetch = async (path, init) => {
    capturedPath = path;
    capturedInit = init;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: 99 }), { status: 200 });
  };

  try {
    const result = await uploadKnowledgeDocument(8, file);
    const body = capturedInit.body;
    assert.equal(result, 99);
    assert.equal(capturedPath, "/api/knowledge/documents/upload");
    assert.equal(capturedInit.method, "POST");
    assert.equal(body.get("promptId"), "8");
    assert.equal(body.get("file"), file);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("createManualKnowledge posts JSON payload", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;
  let capturedInit;

  globalThis.fetch = async (path, init) => {
    capturedPath = path;
    capturedInit = init;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: 100 }), { status: 200 });
  };

  try {
    const payload = { promptId: 3, title: "FAQ", content: "常见问题" };
    const result = await createManualKnowledge(payload);
    assert.equal(result, 100);
    assert.equal(capturedPath, "/api/knowledge/documents/manual");
    assert.equal(capturedInit.method, "POST");
    assert.deepEqual(JSON.parse(capturedInit.body), payload);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("deleteKnowledgeDocument calls delete endpoint", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;
  let capturedInit;

  globalThis.fetch = async (path, init) => {
    capturedPath = path;
    capturedInit = init;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: null }), { status: 200 });
  };

  try {
    const result = await deleteKnowledgeDocument(21);
    assert.equal(result, null);
    assert.equal(capturedPath, "/api/knowledge/documents/21");
    assert.equal(capturedInit.method, "DELETE");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("listKnowledgeSegments sends paging query", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;

  globalThis.fetch = async (path) => {
    capturedPath = path;
    return new Response(
      JSON.stringify({
        code: 0,
        message: "ok",
        data: [{ text: "segment", metadata: "{\"docId\":\"21\"}" }],
      }),
      { status: 200 },
    );
  };

  try {
    const result = await listKnowledgeSegments(21, 10, 20);
    assert.deepEqual(result, [{ text: "segment", metadata: "{\"docId\":\"21\"}" }]);
    assert.equal(capturedPath, "/api/knowledge/documents/21/segments?limit=10&offset=20");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

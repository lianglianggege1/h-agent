import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import { test } from "node:test";

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === "./http" && context.parentURL?.endsWith("/harness-memory.ts")) {
      return nextResolve("./http.ts", context);
    }

    return nextResolve(specifier, context);
  },
});

const { ApiError } = await import("./http.ts");
const {
  HARNESS_MEMORY_MAX_BYTES,
  extractMemorySections,
  getHarnessMemory,
  isHarnessMemoryConflict,
  isHarnessMemoryUnauthorized,
  saveHarnessMemory,
  utf8ByteLength,
} = await import("./harness-memory.ts");

test("getHarnessMemory requests the single document endpoint", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;
  let capturedInit;

  globalThis.fetch = async (path, init) => {
    capturedPath = path;
    capturedInit = init;
    return new Response(
      JSON.stringify({
        code: 0,
        message: "OK",
        data: { content: "# 用户长期记忆", revision: 3, exists: true, updatedAt: "2026-08-31T06:30:00Z" },
      }),
      { status: 200 },
    );
  };

  try {
    const document = await getHarnessMemory();
    assert.deepEqual(document, {
      content: "# 用户长期记忆",
      revision: 3,
      exists: true,
      updatedAt: "2026-08-31T06:30:00Z",
    });
    assert.equal(capturedPath, "/api/me/memory");
    assert.equal(capturedInit.method, undefined);
    assert.equal(capturedInit.credentials, "include");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("saveHarnessMemory puts only content and expectedRevision", async () => {
  const originalFetch = globalThis.fetch;
  let capturedPath;
  let capturedInit;

  globalThis.fetch = async (path, init) => {
    capturedPath = path;
    capturedInit = init;
    return new Response(
      JSON.stringify({
        code: 0,
        message: "OK",
        data: { content: "新内容", revision: 8, exists: true, updatedAt: "2026-08-31T07:00:00Z" },
      }),
      { status: 200 },
    );
  };

  try {
    const document = await saveHarnessMemory("新内容", 7);
    assert.deepEqual(JSON.parse(capturedInit.body), { content: "新内容", expectedRevision: 7 });
    assert.equal(capturedPath, "/api/me/memory");
    assert.equal(capturedInit.method, "PUT");
    assert.equal(capturedInit.credentials, "include");
    assert.equal(document.revision, 8);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("saveHarnessMemory rejects when server reports a conflict", async () => {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async () =>
    new Response(JSON.stringify({ code: 40920, message: "记忆内容已被其他会话更新，请重新加载最新内容后再保存", data: null }), {
      status: 409,
    });

  try {
    let conflict = false;
    let conflictError;
    try {
      await saveHarnessMemory("内容", 7);
    } catch (error) {
      conflictError = error;
    }
    conflict = isHarnessMemoryConflict(conflictError);
    assert.equal(conflict, true);
    assert.equal(isHarnessMemoryUnauthorized(conflictError), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("conflict detection ignores other errors", () => {
  assert.equal(isHarnessMemoryConflict(new ApiError("请求失败", 40100, null)), false);
  assert.equal(isHarnessMemoryConflict(new Error("网络异常")), false);
  assert.equal(isHarnessMemoryConflict(null), false);
});

test("unauthorized detection matches the auth entry point code", () => {
  assert.equal(isHarnessMemoryUnauthorized(new ApiError("Unauthorized", 40100, null)), true);
  assert.equal(isHarnessMemoryUnauthorized(new ApiError("请求失败", 40920, null)), false);
  assert.equal(isHarnessMemoryUnauthorized(new Error("请求失败")), false);
});

test("utf8ByteLength counts bytes for ascii, chinese and emoji", () => {
  assert.equal(utf8ByteLength(""), 0);
  assert.equal(utf8ByteLength("abc"), 3);
  assert.equal(utf8ByteLength("中文"), 6);
  assert.equal(utf8ByteLength("😀"), 4);
  assert.equal(utf8ByteLength("a中😀"), 8);
  assert.equal(utf8ByteLength("a".repeat(65_536)), HARNESS_MEMORY_MAX_BYTES);
});

test("extractMemorySections keeps heading order and duplicates", () => {
  const content = "# 用户长期记忆\n\n## 工作偏好\n\n- 条目\n\n## 工作偏好\n\n## 自定义章节";
  const sections = extractMemorySections(content);
  assert.deepEqual(sections.map((section) => section.title), ["工作偏好", "工作偏好", "自定义章节"]);
});

test("extractMemorySections ignores h1, h3 and fenced code blocks", () => {
  const content = [
    "# 一级标题",
    "## 真章节",
    "### 三级伪章节",
    "```markdown",
    "## 代码块里的伪标题",
    "```",
    "~~~",
    "## 波浪线围栏里的伪标题",
    "~~~",
    "## 尾部章节",
  ].join("\n");
  const sections = extractMemorySections(content);
  assert.deepEqual(sections.map((section) => section.title), ["真章节", "尾部章节"]);
});

test("extractMemorySections returns empty list without h2 headings", () => {
  assert.deepEqual(extractMemorySections(""), []);
  assert.deepEqual(extractMemorySections("# 只有 h1\n正文"), []);
  assert.deepEqual(extractMemorySections("##没有空格不是标题"), []);
});

test("extractMemorySections reports the line start offsets", () => {
  const content = "# 标题\n\n## 工作偏好\n\n内容\n\n## 个人信息";
  const sections = extractMemorySections(content);
  assert.deepEqual(sections, [
    { title: "工作偏好", offset: "# 标题\n\n".length },
    { title: "个人信息", offset: "# 标题\n\n## 工作偏好\n\n内容\n\n".length },
  ]);
});

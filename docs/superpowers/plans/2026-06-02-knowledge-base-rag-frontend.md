# 知识库上传 + RAG 检索前端实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为后端知识库/RAG 能力增加前端管理界面，让用户按 SystemPrompt/Agent 上传文件、手动录入、管理文档并查看切片。

**架构：** 新增独立 `/me/knowledge` 页面，聊天页和“我的”页只提供入口。前端新增 `knowledge.ts` 封装后端知识库 REST 接口，并在 `http.ts` 中补一个不设置 JSON Content-Type 的 multipart 请求封装。页面保持现有窄屏优先风格，按当前 `promptId` 管理文档。

**技术栈：** Next.js 16 App Router + React 19 + TypeScript + Tailwind CSS + Node test runner。

**规格来源：** `docs/superpowers/specs/2026-06-02-knowledge-base-rag-frontend-design.md`

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|------|------|
| `frontend/lib/knowledge.ts` | 知识库 REST API 类型与函数 |
| `frontend/lib/knowledge.test.mjs` | 验证知识库 API 路径、方法、请求体 |
| `frontend/app/me/knowledge/page.tsx` | 知识库管理页面 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `frontend/lib/http.ts` | 增加 `apiFormFetch<T>()`，复用统一响应解析 |
| `frontend/lib/http.test.mjs` | 增加 FormData 不设置 JSON Content-Type 的测试 |
| `frontend/app/chat/page.tsx` | 当前 SystemPrompt 卡片增加“知识库”入口 |
| `frontend/app/me/page.tsx` | 增加“知识库管理”入口 |

---

## 任务 1：HTTP multipart 封装

**文件：**
- 修改：`frontend/lib/http.ts`
- 修改：`frontend/lib/http.test.mjs`

- [ ] **步骤 1：为 `apiFormFetch` 编写失败测试**

在 `frontend/lib/http.test.mjs` 顶部 import 中加入 `apiFormFetch`：

```js
import { apiFetch, apiFormFetch, apiStream } from "./http.ts";
```

在第一个测试后添加：

```js
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd frontend
npm test
```

预期：FAIL，报错包含 `The requested module './http.ts' does not provide an export named 'apiFormFetch'`。

- [ ] **步骤 3：实现 `apiFormFetch`**

在 `frontend/lib/http.ts` 的 `apiFetch` 后新增：

```ts
export async function apiFormFetch<T>(path: string, body: FormData, init: Omit<RequestInit, "body"> = {}) {
  const headers = new Headers(init.headers);

  const response = await fetch(path, {
    ...init,
    method: init.method ?? "POST",
    body,
    headers,
    credentials: "include",
  });
  const parsedBody = await parseApiResponse<T>(response);

  if (!response.ok || parsedBody.code !== 0) {
    throw new Error(parsedBody.message || "请求失败");
  }

  return parsedBody.data as T;
}
```

不要给 `headers` 设置 `Content-Type`，浏览器会为 `FormData` 自动生成 multipart boundary。

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
cd frontend
npm test
```

预期：PASS，所有 `lib/*.test.mjs` 通过。

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/http.ts frontend/lib/http.test.mjs
git commit -m "feat: add multipart API helper"
```

---

## 任务 2：知识库 API 客户端

**文件：**
- 创建：`frontend/lib/knowledge.ts`
- 创建：`frontend/lib/knowledge.test.mjs`

- [ ] **步骤 1：编写失败测试**

创建 `frontend/lib/knowledge.test.mjs`：

```js
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  createManualKnowledge,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  listKnowledgeSegments,
  uploadKnowledgeDocument,
} from "./knowledge.ts";

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
  let capturedInit;

  globalThis.fetch = async (_path, init) => {
    capturedInit = init;
    return new Response(JSON.stringify({ code: 0, message: "ok", data: 99 }), { status: 200 });
  };

  try {
    const result = await uploadKnowledgeDocument(8, file);
    const body = capturedInit.body;
    assert.equal(result, 99);
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
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```bash
cd frontend
npm test
```

预期：FAIL，报错包含 `Cannot find module` 或找不到 `knowledge.ts`。

- [ ] **步骤 3：实现知识库 API 模块**

创建 `frontend/lib/knowledge.ts`：

```ts
import { apiFetch, apiFormFetch } from "./http";

export type KnowledgeDocument = {
  id: number;
  fileName: string;
  sourceType: string;
  fileType: string | null;
  fileSize: number | null;
  charCount: number | null;
  segmentCount: number | null;
  status: string;
  errorMsg: string | null;
  createdAt: string;
};

export type KnowledgeSegment = {
  text: string;
  metadata: string;
};

export type ManualKnowledgePayload = {
  promptId: number;
  title: string;
  content: string;
};

export function listKnowledgeDocuments(promptId: number) {
  const search = new URLSearchParams({ promptId: String(promptId) });
  return apiFetch<KnowledgeDocument[]>(`/api/knowledge/documents?${search.toString()}`);
}

export function uploadKnowledgeDocument(promptId: number, file: File) {
  const form = new FormData();
  form.append("file", file);
  form.append("promptId", String(promptId));
  return apiFormFetch<number>("/api/knowledge/documents/upload", form);
}

export function createManualKnowledge(payload: ManualKnowledgePayload) {
  return apiFetch<number>("/api/knowledge/documents/manual", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function deleteKnowledgeDocument(docId: number) {
  return apiFetch<null>(`/api/knowledge/documents/${docId}`, {
    method: "DELETE",
  });
}

export function listKnowledgeSegments(docId: number, limit = 20, offset = 0) {
  const search = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  return apiFetch<KnowledgeSegment[]>(`/api/knowledge/documents/${docId}/segments?${search.toString()}`);
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```bash
cd frontend
npm test
```

预期：PASS。

- [ ] **步骤 5：Commit**

```bash
git add frontend/lib/knowledge.ts frontend/lib/knowledge.test.mjs
git commit -m "feat: add knowledge API client"
```

---

## 任务 3：知识库页面骨架、鉴权与文档列表

**文件：**
- 创建：`frontend/app/me/knowledge/page.tsx`

- [ ] **步骤 1：创建页面文件并实现鉴权、prompt 选择、文档列表**

创建 `frontend/app/me/knowledge/page.tsx`，先实现页面骨架、鉴权跳转、SystemPrompt pills、文档列表与空状态：

```tsx
"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import {
  KnowledgeDocument,
  listKnowledgeDocuments,
} from "@/lib/knowledge";
import { savePostLoginRedirect } from "@/lib/session";
import { SystemPrompt, listSystemPrompts } from "@/lib/system-prompts";

function formatFileSize(value: number | null) {
  if (!value) return "未知大小";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatDate(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function statusLabel(status: string) {
  if (status === "COMPLETED") return "已完成";
  if (status === "FAILED") return "失败";
  if (status === "PROCESSING") return "处理中";
  return status;
}

export default function KnowledgePage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [error, setError] = useState("");

  const selectedPrompt = useMemo(
    () => prompts.find((prompt) => prompt.id === selectedPromptId) ?? null,
    [prompts, selectedPromptId],
  );

  const refreshDocuments = useCallback(async (promptId: number) => {
    setDocumentsLoading(true);
    setError("");
    try {
      setDocuments(await listKnowledgeDocuments(promptId));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "加载知识文档失败");
    } finally {
      setDocumentsLoading(false);
    }
  }, []);

  useEffect(() => {
    const redirectPath = `/me/knowledge${window.location.search}`;
    getCurrentUser()
      .then(async () => {
        const list = await listSystemPrompts();
        const promptIdFromUrl = Number(new URLSearchParams(window.location.search).get("promptId"));
        const promptFromUrl = list.find((prompt) => prompt.id === promptIdFromUrl) ?? null;
        const defaultPrompt = list.find((prompt) => prompt.isDefault) ?? list[0] ?? null;
        const nextPrompt = promptFromUrl ?? defaultPrompt;

        setAuthenticated(true);
        setPrompts(list);
        setSelectedPromptId(nextPrompt?.id ?? null);
      })
      .catch(() => {
        savePostLoginRedirect(redirectPath);
        router.replace("/auth/login");
      });
  }, [router]);

  useEffect(() => {
    if (!selectedPromptId) return;
    void refreshDocuments(selectedPromptId);
  }, [refreshDocuments, selectedPromptId]);

  function handleSelectPrompt(promptId: number) {
    if (promptId === selectedPromptId) return;
    setSelectedPromptId(promptId);
  }

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href="/me">
            返回我的
          </Link>
          <p className="mt-4 text-xs uppercase tracking-[0.28em] text-amber-700">Knowledge Base</p>
          <h1 className="mt-2 text-2xl font-semibold">知识库管理</h1>
          <p className="mt-2 text-sm leading-6 text-stone-500">
            每个 SystemPrompt 拥有独立知识库，聊天时会按当前 Agent 自动检索。
          </p>
        </header>

        {prompts.length === 0 ? (
          <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)]">
            <p className="text-sm leading-6 text-stone-600">还没有 SystemPrompt，请先创建一个 Agent。</p>
            <Link
              className="mt-4 block rounded-2xl bg-stone-900 px-4 py-3 text-center text-sm font-semibold text-white"
              href="/me/system-prompts"
            >
              去创建 SystemPrompt
            </Link>
          </div>
        ) : (
          <>
            <div className="flex gap-2 overflow-x-auto pb-1">
              {prompts.map((prompt) => (
                <button
                  key={prompt.id}
                  className={`shrink-0 rounded-full border px-4 py-2 text-sm shadow-sm ${
                    prompt.id === selectedPromptId
                      ? "border-stone-900 bg-stone-900 text-white"
                      : "border-stone-200 bg-white/90 text-stone-600"
                  }`}
                  type="button"
                  onClick={() => handleSelectPrompt(prompt.id)}
                >
                  {prompt.name}
                  {prompt.isDefault ? " · 默认" : ""}
                </button>
              ))}
            </div>

            <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)]">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Documents</p>
                  <h2 className="mt-1 text-lg font-semibold">{selectedPrompt?.name ?? "当前 Agent"}</h2>
                </div>
                {documentsLoading ? <p className="text-sm text-stone-500">加载中...</p> : null}
              </div>

              {error ? <p className="mt-3 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

              {!documentsLoading && documents.length === 0 ? (
                <p className="mt-4 rounded-2xl bg-stone-50 px-4 py-5 text-sm leading-6 text-stone-500">
                  暂无知识文档，可以上传文件或手动录入文本。
                </p>
              ) : null}

              <div className="mt-4 space-y-3">
                {documents.map((document) => (
                  <article key={document.id} className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <h3 className="break-words text-sm font-semibold text-stone-800">{document.fileName}</h3>
                        <p className="mt-1 text-xs text-stone-500">
                          {document.sourceType === "MANUAL" ? "手动录入" : "文件上传"} · {statusLabel(document.status)}
                        </p>
                      </div>
                      <span className="shrink-0 rounded-full bg-white px-3 py-1 text-xs text-stone-500">
                        {document.segmentCount ?? 0} 段
                      </span>
                    </div>
                    <dl className="mt-3 grid grid-cols-2 gap-2 text-xs text-stone-500">
                      <div>
                        <dt>类型</dt>
                        <dd className="mt-1 text-stone-700">{document.fileType || "-"}</dd>
                      </div>
                      <div>
                        <dt>大小</dt>
                        <dd className="mt-1 text-stone-700">{formatFileSize(document.fileSize)}</dd>
                      </div>
                      <div>
                        <dt>字符数</dt>
                        <dd className="mt-1 text-stone-700">{document.charCount ?? "-"}</dd>
                      </div>
                      <div>
                        <dt>创建时间</dt>
                        <dd className="mt-1 text-stone-700">{formatDate(document.createdAt)}</dd>
                      </div>
                    </dl>
                    {document.errorMsg ? (
                      <p className="mt-3 rounded-2xl bg-red-50 px-3 py-2 text-xs leading-5 text-red-600">
                        {document.errorMsg}
                      </p>
                    ) : null}
                  </article>
                ))}
              </div>
            </section>
          </>
        )}
      </section>
    </main>
  );
}
```

- [ ] **步骤 2：运行 lint**

运行：

```bash
cd frontend
npm run lint
```

预期：PASS。

- [ ] **步骤 3：运行构建**

运行：

```bash
cd frontend
npm run build
```

预期：PASS。

- [ ] **步骤 4：Commit**

```bash
git add frontend/app/me/knowledge/page.tsx
git commit -m "feat: add knowledge management page shell"
```

---

## 任务 4：上传、手动录入与删除

**文件：**
- 修改：`frontend/app/me/knowledge/page.tsx`

- [ ] **步骤 1：补充 imports**

把 `frontend/app/me/knowledge/page.tsx` 的 React import 改为包含 `FormEvent`：

```tsx
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
```

把知识库 import 改为：

```tsx
import {
  KnowledgeDocument,
  createManualKnowledge,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  uploadKnowledgeDocument,
} from "@/lib/knowledge";
```

- [ ] **步骤 2：新增页面状态**

在 `error` state 后加入：

```tsx
const [message, setMessage] = useState("");
const [selectedFile, setSelectedFile] = useState<File | null>(null);
const [uploading, setUploading] = useState(false);
const [manualOpen, setManualOpen] = useState(false);
const [manualTitle, setManualTitle] = useState("");
const [manualContent, setManualContent] = useState("");
const [savingManual, setSavingManual] = useState(false);
const [deletingDocId, setDeletingDocId] = useState<number | null>(null);
```

不要在 `refreshDocuments` 中清理 `message`，成功消息由上传、录入、删除 handler 设置，下一次用户操作前再清理。

- [ ] **步骤 3：新增上传、手动录入、删除 handler**

在 `handleSelectPrompt` 后加入：

```tsx
async function handleUpload(event: FormEvent<HTMLFormElement>) {
  event.preventDefault();
  if (!selectedPromptId || !selectedFile || uploading) return;

  setUploading(true);
  setError("");
  setMessage("");
  try {
    await uploadKnowledgeDocument(selectedPromptId, selectedFile);
    setSelectedFile(null);
    setMessage("文件已入库");
    await refreshDocuments(selectedPromptId);
  } catch (uploadError) {
    setError(uploadError instanceof Error ? uploadError.message : "上传失败");
  } finally {
    setUploading(false);
  }
}

async function handleManualSubmit(event: FormEvent<HTMLFormElement>) {
  event.preventDefault();
  if (!selectedPromptId || savingManual || !manualTitle.trim() || !manualContent.trim()) return;

  setSavingManual(true);
  setError("");
  setMessage("");
  try {
    await createManualKnowledge({
      promptId: selectedPromptId,
      title: manualTitle.trim(),
      content: manualContent.trim(),
    });
    setManualTitle("");
    setManualContent("");
    setManualOpen(false);
    setMessage("文本知识已入库");
    await refreshDocuments(selectedPromptId);
  } catch (manualError) {
    setError(manualError instanceof Error ? manualError.message : "保存失败");
  } finally {
    setSavingManual(false);
  }
}

async function handleDelete(document: KnowledgeDocument) {
  if (!selectedPromptId || deletingDocId) return;
  const confirmed = window.confirm(`确定删除「${document.fileName}」吗？这会同时移除对应知识库向量。`);
  if (!confirmed) return;

  setDeletingDocId(document.id);
  setError("");
  setMessage("");
  try {
    await deleteKnowledgeDocument(document.id);
    setMessage("文档已删除");
    await refreshDocuments(selectedPromptId);
  } catch (deleteError) {
    setError(deleteError instanceof Error ? deleteError.message : "删除失败");
  } finally {
    setDeletingDocId(null);
  }
}
```

- [ ] **步骤 4：在页面中加入操作区**

在 SystemPrompt pill 列表后、文档列表 section 前加入：

```tsx
<section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)]">
  <div className="grid grid-cols-1 gap-3">
    <form className="rounded-2xl bg-stone-50/80 p-4" onSubmit={handleUpload}>
      <label className="block text-sm font-semibold text-stone-700" htmlFor="knowledge-file">
        上传文件
      </label>
      <p className="mt-1 text-xs leading-5 text-stone-500">支持 md、txt、doc、docx、xls、xlsx，单文件上限以后端配置为准。</p>
      <input
        id="knowledge-file"
        className="mt-3 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm"
        type="file"
        accept=".md,.markdown,.txt,.doc,.docx,.xls,.xlsx"
        onChange={(event) => setSelectedFile(event.target.files?.[0] ?? null)}
      />
      <button
        className="mt-3 w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
        type="submit"
        disabled={!selectedFile || uploading}
      >
        {uploading ? "上传中" : "上传并入库"}
      </button>
    </form>

    <div className="rounded-2xl bg-stone-50/80 p-4">
      <button
        className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left text-sm font-semibold text-stone-700"
        type="button"
        onClick={() => setManualOpen((current) => !current)}
      >
        {manualOpen ? "收起手动录入" : "手动录入文本"}
      </button>

      {manualOpen ? (
        <form className="mt-3 space-y-3" onSubmit={handleManualSubmit}>
          <input
            className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
            value={manualTitle}
            onChange={(event) => setManualTitle(event.target.value)}
            maxLength={120}
            placeholder="标题，例如：产品 FAQ"
          />
          <textarea
            className="min-h-40 w-full resize-none rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
            value={manualContent}
            onChange={(event) => setManualContent(event.target.value)}
            maxLength={20000}
            placeholder="粘贴要加入知识库的文本..."
          />
          <button
            className="w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
            type="submit"
            disabled={savingManual || !manualTitle.trim() || !manualContent.trim()}
          >
            {savingManual ? "保存中" : "保存文本知识"}
          </button>
        </form>
      ) : null}
    </div>
  </div>
</section>
```

- [ ] **步骤 5：展示成功消息并加删除按钮**

在文档列表 section 的错误提示前加入：

```tsx
{message ? <p className="mt-3 rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{message}</p> : null}
```

在每个文档卡片的 metadata 后、失败原因前加入：

```tsx
<div className="mt-3 flex gap-2">
  <button
    className="rounded-2xl border border-red-200 px-3 py-2 text-xs font-semibold text-red-600 disabled:text-red-300"
    type="button"
    disabled={deletingDocId === document.id}
    onClick={() => void handleDelete(document)}
  >
    {deletingDocId === document.id ? "删除中" : "删除"}
  </button>
</div>
```

- [ ] **步骤 6：运行验证**

运行：

```bash
cd frontend
npm run lint
npm run build
```

预期：两条命令均 PASS。

- [ ] **步骤 7：Commit**

```bash
git add frontend/app/me/knowledge/page.tsx
git commit -m "feat: add knowledge document mutations"
```

---

## 任务 5：切片查看面板

**文件：**
- 修改：`frontend/app/me/knowledge/page.tsx`

- [ ] **步骤 1：补充 imports**

把知识库 import 改为包含 `KnowledgeSegment` 和 `listKnowledgeSegments`：

```tsx
import {
  KnowledgeDocument,
  KnowledgeSegment,
  createManualKnowledge,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  listKnowledgeSegments,
  uploadKnowledgeDocument,
} from "@/lib/knowledge";
```

- [ ] **步骤 2：新增切片状态和常量**

在 `deletingDocId` state 后加入：

```tsx
const segmentPageSize = 20;
const [segmentsOpenDocId, setSegmentsOpenDocId] = useState<number | null>(null);
const [segments, setSegments] = useState<KnowledgeSegment[]>([]);
const [segmentsLoading, setSegmentsLoading] = useState(false);
const [segmentsOffset, setSegmentsOffset] = useState(0);
const [segmentsHasMore, setSegmentsHasMore] = useState(false);
const [segmentsError, setSegmentsError] = useState("");
```

- [ ] **步骤 3：新增切片加载 handler**

在 `handleDelete` 后加入：

```tsx
async function loadSegments(document: KnowledgeDocument, reset: boolean) {
  if (segmentsLoading) return;
  const nextOffset = reset ? 0 : segmentsOffset;

  setSegmentsOpenDocId(document.id);
  setSegmentsLoading(true);
  setSegmentsError("");
  try {
    const nextSegments = await listKnowledgeSegments(document.id, segmentPageSize, nextOffset);
    setSegments((current) => (reset ? nextSegments : [...current, ...nextSegments]));
    setSegmentsOffset(nextOffset + nextSegments.length);
    setSegmentsHasMore(nextSegments.length === segmentPageSize);
  } catch (loadError) {
    setSegmentsError(loadError instanceof Error ? loadError.message : "加载切片失败");
  } finally {
    setSegmentsLoading(false);
  }
}

function closeSegments() {
  setSegmentsOpenDocId(null);
  setSegments([]);
  setSegmentsOffset(0);
  setSegmentsHasMore(false);
  setSegmentsError("");
}
```

在 `handleSelectPrompt` 内切换 prompt 前清理切片面板：

```tsx
closeSegments();
```

- [ ] **步骤 4：为完成文档增加查看切片按钮**

把任务 4 中的文档操作区替换为：

```tsx
<div className="mt-3 flex flex-wrap gap-2">
  {document.status === "COMPLETED" ? (
    <button
      className="rounded-2xl border border-stone-200 bg-white px-3 py-2 text-xs font-semibold text-stone-700"
      type="button"
      onClick={() => {
        if (segmentsOpenDocId === document.id) {
          closeSegments();
          return;
        }
        void loadSegments(document, true);
      }}
    >
      {segmentsOpenDocId === document.id ? "收起切片" : "查看切片"}
    </button>
  ) : null}
  <button
    className="rounded-2xl border border-red-200 px-3 py-2 text-xs font-semibold text-red-600 disabled:text-red-300"
    type="button"
    disabled={deletingDocId === document.id}
    onClick={() => void handleDelete(document)}
  >
    {deletingDocId === document.id ? "删除中" : "删除"}
  </button>
</div>
```

- [ ] **步骤 5：在文档卡片下方渲染切片面板**

在失败原因渲染之后加入：

```tsx
{segmentsOpenDocId === document.id ? (
  <div className="mt-3 rounded-2xl border border-stone-200 bg-white p-3">
    <div className="flex items-center justify-between gap-3">
      <p className="text-sm font-semibold text-stone-700">切片内容</p>
      {segmentsLoading ? <p className="text-xs text-stone-500">加载中...</p> : null}
    </div>
    {segmentsError ? <p className="mt-2 text-xs text-red-600">{segmentsError}</p> : null}
    {!segmentsLoading && segments.length === 0 && !segmentsError ? (
      <p className="mt-2 text-xs text-stone-500">暂无切片。</p>
    ) : null}
    <div className="mt-3 max-h-96 space-y-3 overflow-y-auto pr-1">
      {segments.map((segment, index) => (
        <article key={`${document.id}-${index}`} className="rounded-2xl bg-stone-50 px-3 py-2">
          <p className="text-xs font-semibold text-stone-500">#{index + 1}</p>
          <p className="mt-2 whitespace-pre-wrap break-words text-xs leading-6 text-stone-700">{segment.text}</p>
          <p className="mt-2 break-words rounded-xl bg-white px-2 py-1 font-mono text-[11px] leading-5 text-stone-400">
            {segment.metadata}
          </p>
        </article>
      ))}
    </div>
    {segmentsHasMore ? (
      <button
        className="mt-3 w-full rounded-2xl border border-stone-200 px-3 py-2 text-xs font-semibold text-stone-700"
        type="button"
        disabled={segmentsLoading}
        onClick={() => void loadSegments(document, false)}
      >
        加载更多
      </button>
    ) : null}
  </div>
) : null}
```

- [ ] **步骤 6：运行验证**

运行：

```bash
cd frontend
npm run lint
npm run build
```

预期：两条命令均 PASS。

- [ ] **步骤 7：Commit**

```bash
git add frontend/app/me/knowledge/page.tsx
git commit -m "feat: add knowledge segment viewer"
```

---

## 任务 6：页面入口

**文件：**
- 修改：`frontend/app/chat/page.tsx`
- 修改：`frontend/app/me/page.tsx`

- [ ] **步骤 1：聊天页当前 SystemPrompt 卡片增加入口**

在 `frontend/app/chat/page.tsx` 中，找到 SystemPrompt 卡片顶部右侧的“管理”链接：

```tsx
<Link className="text-sm font-medium text-amber-700" href="/me/system-prompts">
  管理
</Link>
```

替换为：

```tsx
<div className="flex shrink-0 items-center gap-3">
  {selectedPromptId ? (
    <Link className="text-sm font-medium text-amber-700" href={`/me/knowledge?promptId=${selectedPromptId}`}>
      知识库
    </Link>
  ) : null}
  <Link className="text-sm font-medium text-amber-700" href="/me/system-prompts">
    管理
  </Link>
</div>
```

- [ ] **步骤 2：“我的”页增加知识库管理入口**

在 `frontend/app/me/page.tsx` 中，在 `SystemPrompt 管理` 链接后、`返回聊天` 链接前加入：

```tsx
<Link
  className="mt-3 block rounded-2xl border border-stone-200 px-4 py-4 text-sm font-semibold text-stone-700"
  href="/me/knowledge"
>
  知识库管理
</Link>
```

- [ ] **步骤 3：运行验证**

运行：

```bash
cd frontend
npm run lint
npm run build
```

预期：两条命令均 PASS。

- [ ] **步骤 4：Commit**

```bash
git add frontend/app/chat/page.tsx frontend/app/me/page.tsx
git commit -m "feat: add knowledge management entry points"
```

---

## 任务 7：最终验证与浏览器验收

**文件：**
- 不新增文件
- 如果验收发现问题，回到对应任务的文件修复，并重新运行本任务的验证步骤。

- [ ] **步骤 1：运行完整前端测试**

运行：

```bash
cd frontend
npm test
npm run lint
npm run build
```

预期：三条命令均 PASS。

- [ ] **步骤 2：启动前端开发服务器**

运行：

```bash
cd frontend
npm run dev
```

预期：Next dev server 启动，终端输出本地访问地址，通常为 `http://localhost:3000`。

- [ ] **步骤 3：浏览器手动验收**

在浏览器中检查：

1. 未登录访问 `/me/knowledge` 跳转登录。
2. 登录后从 `/me` 能进入“知识库管理”。
3. 登录后从 `/chat` 当前 SystemPrompt 卡片能进入 `/me/knowledge?promptId=<当前 promptId>`。
4. `/me/knowledge` 默认选中 URL prompt、默认 prompt 或首个 prompt。
5. 切换 prompt 会刷新文档列表。
6. 上传允许类型文件后列表刷新并出现新文档。
7. 手动录入文本后列表刷新并出现新文档。
8. 已完成文档可以展开切片面板，并能加载更多。
9. 删除文档会先确认，确认后列表刷新。
10. 后端错误 message 会显示在页面中。

- [ ] **步骤 4：停止开发服务器**

如果 dev server 是前台进程，按 `Ctrl-C` 停止。不要留下仍在运行且不再需要的终端会话。

- [ ] **步骤 5：最终状态检查**

运行：

```bash
git status --short
```

预期：没有未提交的实现文件。若只有用户已有的 `.cursor/` 未跟踪目录，可以保持不处理。

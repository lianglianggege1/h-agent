"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { KnowledgeDocument, listKnowledgeDocuments } from "@/lib/knowledge";
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

function getStatusLabel(status: string) {
  if (status === "COMPLETED") return "已完成";
  if (status === "FAILED") return "失败";
  if (status === "PROCESSING") return "处理中";
  return status;
}

function getSourceTypeLabel(sourceType: string) {
  if (sourceType === "MANUAL") return "手动录入";
  if (sourceType === "FILE") return "文件上传";
  return sourceType;
}

function getStatusClassName(status: string) {
  if (status === "COMPLETED") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (status === "FAILED") return "border-red-200 bg-red-50 text-red-600";
  if (status === "PROCESSING") return "border-amber-200 bg-amber-50 text-amber-700";
  return "border-stone-200 bg-white text-stone-600";
}

function getPromptIdFromSearch(search: string, promptList: SystemPrompt[]) {
  const promptIdFromUrl = Number(new URLSearchParams(search).get("promptId"));
  if (!Number.isFinite(promptIdFromUrl)) return null;
  return promptList.find((prompt) => prompt.id === promptIdFromUrl)?.id ?? null;
}

function getPreferredPromptId(promptList: SystemPrompt[], search: string) {
  const promptIdFromUrl = getPromptIdFromSearch(search, promptList);
  if (promptIdFromUrl) return promptIdFromUrl;
  const defaultPrompt = promptList.find((prompt) => prompt.isDefault) ?? promptList[0] ?? null;
  return defaultPrompt?.id ?? null;
}

function buildKnowledgeUrl(promptId: number | null) {
  return promptId ? `/me/knowledge?promptId=${promptId}` : "/me/knowledge";
}

export default function KnowledgePage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [promptsLoading, setPromptsLoading] = useState(true);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [error, setError] = useState("");
  const mountedRef = useRef(false);
  const requestIdRef = useRef(0);
  const selectedPromptIdRef = useRef<number | null>(null);

  const selectedPrompt = useMemo(
    () => prompts.find((prompt) => prompt.id === selectedPromptId) ?? null,
    [prompts, selectedPromptId],
  );

  const refreshDocuments = useCallback(async (promptId: number) => {
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;

    if (!mountedRef.current) return;
    setDocumentsLoading(true);
    setError("");

    try {
      const nextDocuments = await listKnowledgeDocuments(promptId);
      if (!mountedRef.current || requestIdRef.current !== requestId) return;
      setDocuments(nextDocuments);
      setError("");
    } catch (loadError) {
      if (!mountedRef.current || requestIdRef.current !== requestId) return;
      setDocuments([]);
      setError(loadError instanceof Error ? loadError.message : "加载知识文档失败");
    } finally {
      if (mountedRef.current && requestIdRef.current === requestId) {
        setDocumentsLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    const redirectPath = `/me/knowledge${window.location.search}`;

    getCurrentUser()
      .then(async () => {
        const list = await listSystemPrompts();
        if (!mountedRef.current) return;

        const nextPromptId = getPreferredPromptId(list, window.location.search);

        setAuthenticated(true);
        setPrompts(list);
        setSelectedPromptId(nextPromptId);
        selectedPromptIdRef.current = nextPromptId;
        setDocuments([]);
        setError("");
        window.history.replaceState(null, "", buildKnowledgeUrl(nextPromptId));

        if (nextPromptId) {
          await refreshDocuments(nextPromptId);
        }
      })
      .catch(() => {
        if (!mountedRef.current) return;
        savePostLoginRedirect(redirectPath);
        router.replace("/auth/login");
      })
      .finally(() => {
        if (mountedRef.current) {
          setPromptsLoading(false);
        }
      });

    return () => {
      mountedRef.current = false;
    };
  }, [refreshDocuments, router]);

  useEffect(() => {
    selectedPromptIdRef.current = selectedPromptId;
  }, [selectedPromptId]);

  useEffect(() => {
    if (!authenticated || prompts.length === 0) return;

    function handlePopState() {
      const nextPromptId = getPreferredPromptId(prompts, window.location.search);
      if (window.location.pathname === "/me/knowledge") {
        window.history.replaceState(null, "", buildKnowledgeUrl(nextPromptId));
      }
      if (nextPromptId === selectedPromptIdRef.current) return;

      setSelectedPromptId(nextPromptId);
      selectedPromptIdRef.current = nextPromptId;
      setDocuments([]);
      setError("");

      if (nextPromptId) {
        void refreshDocuments(nextPromptId);
      } else {
        requestIdRef.current += 1;
        setDocumentsLoading(false);
      }
    }

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [authenticated, prompts, refreshDocuments]);

  function handleSelectPrompt(promptId: number) {
    if (promptId === selectedPromptId) return;
    setSelectedPromptId(promptId);
    selectedPromptIdRef.current = promptId;
    setDocuments([]);
    setError("");
    window.history.replaceState(null, "", buildKnowledgeUrl(promptId));
    void refreshDocuments(promptId);
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
            每个 SystemPrompt 拥有独立知识库，聊天检索会跟随当前 Agent 走。
          </p>
        </header>

        {promptsLoading ? (
          <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)]">
            <p className="text-sm text-stone-500">加载中...</p>
          </section>
        ) : null}

        {!promptsLoading && prompts.length === 0 ? (
          <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)]">
            <p className="text-sm leading-6 text-stone-600">还没有可绑定知识库的 SystemPrompt，请先创建一个 Agent。</p>
            <Link
              className="mt-4 block rounded-2xl bg-stone-900 px-4 py-3 text-center text-sm font-semibold text-white"
              href="/me/system-prompts"
            >
              去创建 SystemPrompt
            </Link>
          </section>
        ) : null}

        {prompts.length > 0 ? (
          <>
            <div className="flex gap-2 overflow-x-auto pb-1">
              {prompts.map((prompt) => (
                <button
                  key={prompt.id}
                  className={`shrink-0 rounded-full border px-4 py-2 text-sm shadow-sm transition ${
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
                <div className="min-w-0">
                  <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Documents</p>
                  <h2 className="mt-1 truncate text-lg font-semibold">{selectedPrompt?.name ?? "当前 Agent"}</h2>
                </div>
                {documentsLoading ? <p className="shrink-0 text-sm text-stone-500">加载中...</p> : null}
              </div>

              {error ? <p className="mt-3 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

              {!documentsLoading && !error && documents.length === 0 ? (
                <p className="mt-4 rounded-2xl bg-stone-50 px-4 py-5 text-sm leading-6 text-stone-500">
                  这个知识库还是空的。你可以稍后添加文件或录入文本。
                </p>
              ) : null}

              <div className="mt-4 space-y-3">
                {documents.map((document) => (
                  <article key={document.id} className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <h3 className="break-words text-sm font-semibold text-stone-800">{document.fileName}</h3>
                        <div className="mt-2 flex flex-wrap gap-2">
                          <span className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs text-stone-600">
                            {getSourceTypeLabel(document.sourceType)}
                          </span>
                          <span
                            className={`rounded-full border px-3 py-1 text-xs font-medium ${getStatusClassName(document.status)}`}
                          >
                            {getStatusLabel(document.status)}
                          </span>
                        </div>
                      </div>
                      <span className="shrink-0 rounded-full bg-white px-3 py-1 text-xs text-stone-500">
                        {document.segmentCount ?? 0} 段
                      </span>
                    </div>

                    <dl className="mt-4 grid grid-cols-2 gap-3 text-xs text-stone-500">
                      <div>
                        <dt>文件类型</dt>
                        <dd className="mt-1 break-words text-sm text-stone-700">{document.fileType || "-"}</dd>
                      </div>
                      <div>
                        <dt>文件大小</dt>
                        <dd className="mt-1 text-sm text-stone-700">{formatFileSize(document.fileSize)}</dd>
                      </div>
                      <div>
                        <dt>字符数</dt>
                        <dd className="mt-1 text-sm text-stone-700">{document.charCount ?? "-"}</dd>
                      </div>
                      <div>
                        <dt>切片数</dt>
                        <dd className="mt-1 text-sm text-stone-700">{document.segmentCount ?? "-"}</dd>
                      </div>
                      <div className="col-span-2">
                        <dt>创建时间</dt>
                        <dd className="mt-1 text-sm text-stone-700">{formatDate(document.createdAt)}</dd>
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
        ) : null}
      </section>
    </main>
  );
}

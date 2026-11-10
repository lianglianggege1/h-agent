"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import {
  KnowledgeDocument,
  KnowledgeSegment,
  createManualKnowledge,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  listKnowledgeSegments,
  uploadKnowledgeDocument,
} from "@/lib/knowledge";
import { savePostLoginRedirect } from "@/lib/session";

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
  if (sourceType === "AGENT") return "Agent 沉淀";
  return sourceType;
}

function getStatusClassName(status: string) {
  if (status === "COMPLETED") return "border-stone-200 bg-stone-50 text-stone-600";
  if (status === "FAILED") return "border-red-200 bg-red-50 text-red-600";
  if (status === "PROCESSING") return "border-stone-200 bg-stone-50 text-stone-600";
  return "border-stone-200 bg-white text-stone-600";
}

function segmentLabel(segment: KnowledgeSegment, position: number) {
  try {
    const index = String(JSON.parse(segment.metadata).index ?? "");
    if (/^\d{1,9}$/.test(index)) return `片段 ${Number(index) + 1}`;
  } catch { /* Older documents may not contain an original ordering index. */ }
  return `展示片段 ${position + 1}（原顺序不可用）`;
}

const segmentPageSize = 20;

export default function KnowledgePage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [initialLoading, setInitialLoading] = useState(true);
  const [documentsLoading, setDocumentsLoading] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [manualOpen, setManualOpen] = useState(false);
  const [manualTitle, setManualTitle] = useState("");
  const [manualContent, setManualContent] = useState("");
  const [savingManual, setSavingManual] = useState(false);
  const [deletingDocId, setDeletingDocId] = useState<number | null>(null);
  const [segmentsOpenDocId, setSegmentsOpenDocId] = useState<number | null>(null);
  const [segments, setSegments] = useState<KnowledgeSegment[]>([]);
  const [segmentsLoading, setSegmentsLoading] = useState(false);
  const [, setSegmentsOffset] = useState(0);
  const [segmentsHasMore, setSegmentsHasMore] = useState(false);
  const [segmentsError, setSegmentsError] = useState("");
  const mountedRef = useRef(false);
  const requestIdRef = useRef(0);
  const segmentsRequestIdRef = useRef(0);
  const segmentsOpenDocIdRef = useRef<number | null>(null);
  const segmentsLoadingRef = useRef(false);
  const segmentsOffsetRef = useRef(0);

  const refreshDocuments = useCallback(async () => {
    const requestId = requestIdRef.current + 1;
    requestIdRef.current = requestId;

    if (!mountedRef.current) return;
    setDocumentsLoading(true);
    setError("");

    try {
      const nextDocuments = await listKnowledgeDocuments();
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

  const closeSegments = useCallback(() => {
    segmentsRequestIdRef.current += 1;
    segmentsOpenDocIdRef.current = null;
    segmentsLoadingRef.current = false;
    segmentsOffsetRef.current = 0;
    setSegmentsOpenDocId(null);
    setSegments([]);
    setSegmentsLoading(false);
    setSegmentsOffset(0);
    setSegmentsHasMore(false);
    setSegmentsError("");
  }, []);

  const loadSegments = useCallback(
    async (document: KnowledgeDocument, reset: boolean) => {
      if (segmentsLoadingRef.current && (!reset || segmentsOpenDocIdRef.current === document.id)) return;

      const nextOffset = reset ? 0 : segmentsOffsetRef.current;
      const requestId = segmentsRequestIdRef.current + 1;
      segmentsRequestIdRef.current = requestId;
      segmentsOpenDocIdRef.current = document.id;
      segmentsLoadingRef.current = true;
      if (reset) {
        segmentsOffsetRef.current = 0;
      }

      if (!mountedRef.current) return;
      setSegmentsOpenDocId(document.id);
      setSegmentsLoading(true);
      setSegmentsError("");
      if (reset) {
        setSegments([]);
        setSegmentsOffset(0);
        setSegmentsHasMore(false);
      }

      try {
        const nextSegments = await listKnowledgeSegments(document.id, segmentPageSize, nextOffset);
        if (
          !mountedRef.current ||
          segmentsRequestIdRef.current !== requestId ||
          segmentsOpenDocIdRef.current !== document.id
        ) {
          return;
        }

        setSegments((currentSegments) => (reset ? nextSegments : [...currentSegments, ...nextSegments]));
        const nextOffsetValue = nextOffset + nextSegments.length;
        segmentsOffsetRef.current = nextOffsetValue;
        setSegmentsOffset(nextOffsetValue);
        setSegmentsHasMore(nextSegments.length === segmentPageSize);
        setSegmentsError("");
      } catch (segmentsLoadError) {
        if (
          !mountedRef.current ||
          segmentsRequestIdRef.current !== requestId ||
          segmentsOpenDocIdRef.current !== document.id
        ) {
          return;
        }
        setSegmentsError(segmentsLoadError instanceof Error ? segmentsLoadError.message : "加载切片失败");
      } finally {
        if (
          mountedRef.current &&
          segmentsRequestIdRef.current === requestId &&
          segmentsOpenDocIdRef.current === document.id
        ) {
          segmentsLoadingRef.current = false;
          setSegmentsLoading(false);
        }
      }
    },
    [],
  );

  useEffect(() => {
    mountedRef.current = true;
    getCurrentUser()
      .then(async () => {
        if (!mountedRef.current) return;
        setAuthenticated(true);
        // Old prompt-scoped links now open the same user-owned knowledge library.
        window.history.replaceState(null, "", "/me/knowledge");
        await refreshDocuments();
      })
      .catch(() => {
        if (!mountedRef.current) return;
        savePostLoginRedirect("/me/knowledge");
        router.replace("/auth/login");
      })
      .finally(() => { if (mountedRef.current) setInitialLoading(false); });
    return () => { mountedRef.current = false; };
  }, [refreshDocuments, router]);

  async function handleUpload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedFile || uploading) return;
    const form = event.currentTarget;
    setUploading(true); setError(""); setMessage("");
    try {
      await uploadKnowledgeDocument(selectedFile);
      if (!mountedRef.current) return;
      form.reset(); setSelectedFile(null); setMessage("文件已入库");
      await refreshDocuments();
    } catch (error) {
      if (mountedRef.current) setError(error instanceof Error ? error.message : "上传失败");
    } finally { if (mountedRef.current) setUploading(false); }
  }

  async function handleManualSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (savingManual || !manualTitle.trim() || !manualContent.trim()) return;
    setSavingManual(true); setError(""); setMessage("");
    try {
      await createManualKnowledge({ title: manualTitle.trim(), content: manualContent.trim() });
      if (!mountedRef.current) return;
      setManualTitle(""); setManualContent(""); setManualOpen(false); setMessage("文本知识已入库");
      await refreshDocuments();
    } catch (error) {
      if (mountedRef.current) setError(error instanceof Error ? error.message : "保存失败");
    } finally { if (mountedRef.current) setSavingManual(false); }
  }

  async function handleDelete(document: KnowledgeDocument) {
    if (deletingDocId) return;
    if (!window.confirm(`确定删除「${document.fileName}」吗？这会同时移除对应知识库向量。`)) return;
    if (segmentsOpenDocIdRef.current === document.id) closeSegments();
    setDeletingDocId(document.id); setError(""); setMessage("");
    try {
      await deleteKnowledgeDocument(document.id);
      if (!mountedRef.current) return;
      setMessage("文档已删除"); await refreshDocuments();
    } catch (error) {
      if (mountedRef.current) setError(error instanceof Error ? error.message : "删除失败");
    } finally { if (mountedRef.current) setDeletingDocId(null); }
  }

  if (!authenticated) {
    return <main className="workspace-page resource-page" />;
  }

  return (
    <main className="workspace-page resource-page text-stone-900">
      <section className="workspace-container space-y-4">
        <header className="rounded-2xl border border-stone-200/80 bg-white p-5 shadow-sm">
          <Link className="text-sm text-stone-600" href="/me">
            返回我的
          </Link>
          <h1 className="mt-2 text-2xl font-semibold">知识库管理</h1>
          <p className="mt-2 text-sm leading-6 text-stone-500">
            知识只属于当前用户，可由不同助手按需查询。你可以上传资料、手工录入，也可以让助手沉淀可复用的知识。
          </p>
        </header>

        {initialLoading ? (
          <section className="rounded-2xl border border-stone-200/80 bg-white p-5 shadow-sm">
            <p className="text-sm text-stone-500">加载中...</p>
          </section>
        ) : null}

        <>
            <section className="rounded-2xl border border-stone-200/80 bg-white p-4 shadow-sm">
              <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
                <form className="rounded-2xl bg-stone-50 p-4" onSubmit={handleUpload}>
                  <label className="block text-sm font-semibold text-stone-700" htmlFor="knowledge-file">
                    上传文件
                  </label>
                  <p className="mt-1 text-xs leading-5 text-stone-500">
                    支持 md、txt、doc、docx、xls、xlsx，单文件上限以后端配置为准。
                  </p>
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

                <div className="rounded-2xl bg-stone-50 p-4">
                  <button
                    className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left text-sm font-semibold text-stone-700"
                    type="button"
                    onClick={() => {
                      setMessage("");
                      setManualOpen((current) => !current);
                    }}
                  >
                    {manualOpen ? "收起手动录入" : "手动录入文本"}
                  </button>

                  {manualOpen ? (
                    <form className="mt-3 space-y-3" onSubmit={handleManualSubmit}>
                      <input
                        className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-stone-200 focus:ring-4 focus:ring-amber-100"
                        value={manualTitle}
                        onChange={(event) => setManualTitle(event.target.value)}
                        maxLength={120}
                        placeholder="标题，例如：产品 FAQ"
                      />
                      <textarea
                        className="min-h-40 w-full resize-none rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none transition focus:border-stone-200 focus:ring-4 focus:ring-amber-100"
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

            <section className="rounded-2xl border border-stone-200/80 bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="mt-1 truncate text-lg font-semibold">我的知识文档</h2>
                </div>
                {documentsLoading ? <p className="shrink-0 text-sm text-stone-500">加载中...</p> : null}
              </div>

              {message ? <p className="mt-3 rounded-2xl bg-stone-50 px-4 py-3 text-sm text-stone-600">{message}</p> : null}
              {error ? <p className="mt-3 rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

              {!documentsLoading && !error && documents.length === 0 ? (
                <p className="mt-4 rounded-2xl bg-stone-50 px-4 py-5 text-sm leading-6 text-stone-500">
                  这个知识库还是空的。你可以稍后添加文件或录入文本。
                </p>
              ) : null}

              <div className="workspace-card-grid mt-4">
                {documents.map((document) => (
                  <article key={document.id} className="rounded-2xl border border-stone-200 bg-stone-50 p-4">
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

                    <div className="mt-3 flex flex-wrap gap-2">
                      {document.status === "COMPLETED" ? (
                        <button
                          className="rounded-2xl border border-stone-300 bg-white px-3 py-2 text-xs font-semibold text-stone-700 disabled:text-stone-300"
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

                    {document.errorMsg ? (
                      <p className="mt-3 rounded-2xl bg-red-50 px-3 py-2 text-xs leading-5 text-red-600">
                        {document.errorMsg}
                      </p>
                    ) : null}

                    {segmentsOpenDocId === document.id ? (
                      <section className="mt-4 rounded-2xl border border-stone-200 bg-white p-3">
                        <div className="flex items-center justify-between gap-3">
                          <h4 className="text-sm font-semibold text-stone-800">切片内容</h4>
                          {segmentsLoading ? <p className="shrink-0 text-xs text-stone-500">加载中...</p> : null}
                        </div>

                        {segmentsError ? (
                          <p className="mt-3 rounded-2xl bg-red-50 px-3 py-2 text-xs leading-5 text-red-600">
                            {segmentsError}
                          </p>
                        ) : null}

                        {!segmentsLoading && !segmentsError && segments.length === 0 ? (
                          <p className="mt-3 rounded-2xl bg-stone-50 px-3 py-4 text-xs leading-5 text-stone-500">
                            暂无切片内容。
                          </p>
                        ) : null}

                        {segments.length > 0 ? (
                          <div className="mt-3 space-y-3">
                            {segments.map((segment, index) => (
                              <article
                                key={`${document.id}-${index}`}
                                className="rounded-2xl border border-stone-100 bg-stone-50 p-3"
                              >
                                <p className="text-xs font-semibold text-stone-500">{segmentLabel(segment, index)}</p>
                                <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-stone-800">
                                  {segment.text}
                                </p>
                                {segment.metadata ? (
                                  <pre className="mt-3 whitespace-pre-wrap break-words rounded-xl bg-white px-3 py-2 font-mono text-[11px] leading-5 text-stone-500">
                                    {segment.metadata}
                                  </pre>
                                ) : null}
                              </article>
                            ))}
                          </div>
                        ) : null}

                        {segmentsHasMore ? (
                          <button
                            className="mt-3 w-full rounded-2xl border border-stone-200 bg-stone-50 px-4 py-2 text-xs font-semibold text-stone-700 disabled:text-stone-300"
                            type="button"
                            disabled={segmentsLoading}
                            onClick={() => void loadSegments(document, false)}
                          >
                            {segmentsLoading ? "加载中" : "加载更多"}
                          </button>
                        ) : null}
                      </section>
                    ) : null}
                  </article>
                ))}
              </div>
            </section>
        </>
      </section>
    </main>
  );
}

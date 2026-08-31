"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";
import { MarkdownContent } from "@/components/markdown-content";
import { getCurrentUser } from "@/lib/auth";
import {
  HARNESS_MEMORY_MAX_BYTES,
  getHarnessMemory,
  extractMemorySections,
  isHarnessMemoryConflict,
  saveHarnessMemory,
  utf8ByteLength,
} from "@/lib/harness-memory";
import {
  beginSaving,
  cancelEditing,
  conflictReloaded,
  conflictResumeEditing,
  initialMemoryPageState,
  isMemoryDraftDirty,
  memoryLoadFailed,
  memoryLoaded,
  saveConflict,
  saveFailed,
  saveSucceeded,
  staleEditReloaded,
  startEditing,
  updateDraft,
  type MemoryPageState,
} from "@/lib/harness-memory-state";
import { savePostLoginRedirect } from "@/lib/session";

const SAVED_FLASH_TIMEOUT_MS = 2_500;

function formatUpdatedAt(updatedAt: string | null): string {
  if (updatedAt === null) {
    return "尚未保存";
  }
  const parsed = new Date(updatedAt);
  if (Number.isNaN(parsed.getTime())) {
    return "尚未保存";
  }
  return `最后更新：${parsed.toLocaleString("zh-CN", { dateStyle: "medium", timeStyle: "short" })}`;
}

export default function MemoryPage() {
  const router = useRouter();
  const [state, setState] = useState<MemoryPageState>(initialMemoryPageState);
  const [authenticated, setAuthenticated] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [reloadError, setReloadError] = useState("");
  const [savedFlash, setSavedFlash] = useState(false);
  const markdownRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const dirty = isMemoryDraftDirty(state);
  const overLimit = utf8ByteLength(state.draft) > HARNESS_MEMORY_MAX_BYTES;
  const saving = state.mode === "saving";
  const canSave = state.mode === "edit" && !overLimit && !state.revisionStale;

  const load = useCallback(async () => {
    setState(initialMemoryPageState());
    try {
      const document = await getHarnessMemory();
      setState((previous) => memoryLoaded(previous, document));
      setReloadError("");
    } catch {
      setState((previous) => memoryLoadFailed(previous));
    }
  }, []);

  useEffect(() => {
    getCurrentUser()
      .then(() => {
        setAuthenticated(true);
        return load();
      })
      .catch(() => {
        savePostLoginRedirect("/me/memory");
        router.replace("/auth/login");
      });
  }, [load, router]);

  useEffect(() => {
    if (!dirty) {
      return;
    }
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  const handleSave = useCallback(async () => {
    if (state.mode !== "edit" || overLimit || state.revisionStale || state.document === null) {
      return;
    }
    const draft = state.draft;
    const expectedRevision = state.document.revision;
    setState((previous) => beginSaving(previous));
    setSaveError("");
    try {
      const document = await saveHarnessMemory(draft, expectedRevision);
      setState((previous) => saveSucceeded(previous, document));
      setSavedFlash(true);
      window.setTimeout(() => setSavedFlash(false), SAVED_FLASH_TIMEOUT_MS);
    } catch (error) {
      if (isHarnessMemoryConflict(error)) {
        setState((previous) => saveConflict(previous));
      } else {
        setState((previous) => saveFailed(previous));
        setSaveError(error instanceof Error && error.message ? error.message : "保存失败，请稍后重试");
      }
    }
  }, [overLimit, state]);

  const handleConflictReload = useCallback(async () => {
    setReloadError("");
    try {
      const document = await getHarnessMemory();
      setState((previous) => {
        if (previous.mode === "conflict") {
          return conflictReloaded(previous, document);
        }
        return staleEditReloaded(previous, document);
      });
    } catch {
      setReloadError("重新加载失败，请稍后重试");
    }
  }, []);

  function confirmLeave(): boolean {
    if (!dirty) {
      return true;
    }
    return window.confirm("有未保存的修改，确认离开将丢弃本地草稿。");
  }

  function handleSectionClick(index: number, offset: number) {
    if (state.mode === "edit") {
      const textarea = textareaRef.current;
      if (textarea === null) {
        return;
      }
      textarea.focus();
      textarea.setSelectionRange(offset, offset);
      return;
    }
    if (state.mode !== "read") {
      return;
    }
    const heading = markdownRef.current?.querySelectorAll("h2")[index];
    heading?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function handleTextareaKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== "s") {
      return;
    }
    event.preventDefault();
    void handleSave();
  }

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  const readSections = state.mode === "read" && state.document !== null
    ? extractMemorySections(state.document.content)
    : [];
  const editSections = state.mode === "edit" || state.mode === "saving" || state.mode === "conflict"
    ? extractMemorySections(state.draft)
    : [];
  const sections = state.mode === "read" ? readSections : editSections;

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-5">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link
            className="text-xs font-semibold uppercase tracking-[0.28em] text-amber-700"
            href="/me"
            onClick={(event) => {
              if (!confirmLeave()) {
                event.preventDefault();
              }
            }}
          >
            返回我的
          </Link>
          <h1 className="mt-2 text-2xl font-semibold">用户长期记忆</h1>
          <p className="mt-1 text-sm text-stone-500">Harness Agent 跨会话共享的 MEMORY.md</p>
          <p className="mt-3 text-xs text-stone-400">
            {state.document === null ? "" : formatUpdatedAt(state.document.updatedAt)}
          </p>
        </header>

        {state.mode === "loading" && (
          <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            <p className="text-sm text-stone-500">正在加载长期记忆…</p>
          </div>
        )}

        {state.mode === "error" && (
          <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            <p className="text-sm font-medium text-stone-700">{state.loadError}</p>
            <button
              className="mt-3 rounded-xl bg-stone-900 px-4 py-2 text-sm font-semibold text-white"
              type="button"
              onClick={() => void load()}
            >
              重试
            </button>
          </div>
        )}

        {sections.length > 0 && state.mode !== "error" && state.mode !== "loading" && (
          <nav className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            <p className="text-xs font-semibold uppercase tracking-[0.28em] text-amber-700">章节</p>
            <div className="mt-2 flex flex-wrap gap-2">
              {sections.map((section, index) => (
                <button
                  className="rounded-full border border-stone-200 px-3 py-1 text-xs font-medium text-stone-700 hover:border-amber-300 hover:text-amber-700"
                  key={`${section.title}-${index}`}
                  type="button"
                  onClick={() => handleSectionClick(index, section.offset)}
                >
                  {section.title}
                </button>
              ))}
            </div>
          </nav>
        )}

        {state.mode === "read" && state.document !== null && (
          <article className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            {state.document.content.trim() === "" ? (
              <p className="text-sm text-stone-500">暂无记忆内容，点击下方按钮开始记录。</p>
            ) : (
              <div ref={markdownRef} className="text-sm leading-6 text-stone-800">
                <MarkdownContent content={state.document.content} />
              </div>
            )}
            <button
              className="mt-4 w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white"
              type="button"
              onClick={() => setState((previous) => startEditing(previous))}
            >
              编辑
            </button>
          </article>
        )}

        {(state.mode === "edit" || state.mode === "saving") && (
          <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            {state.revisionStale && (
              <div className="mb-3 rounded-2xl border border-amber-200 bg-amber-50 px-3 py-2">
                <p className="text-xs leading-5 text-amber-800">
                  记忆已被其他会话更新，当前草稿无法保存；重新加载最新内容后再编辑。
                </p>
                <button
                  className="mt-2 rounded-xl bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white"
                  type="button"
                  onClick={() => void handleConflictReload()}
                >
                  重新加载最新内容
                </button>
                {reloadError && <p className="mt-2 text-xs text-amber-800">{reloadError}</p>}
              </div>
            )}
            {saveError && (
              <p className="mb-3 rounded-2xl border border-stone-200 bg-stone-50 px-3 py-2 text-xs leading-5 text-stone-600">
                {saveError}
              </p>
            )}
            <div className="flex items-center justify-between text-xs text-stone-500">
              <span>{saving ? "正在保存…" : "Markdown"}</span>
              <span className={overLimit ? "font-semibold text-red-600" : ""}>
                {utf8ByteLength(state.draft).toLocaleString()} / {HARNESS_MEMORY_MAX_BYTES.toLocaleString()} 字节
              </span>
            </div>
            {overLimit && (
              <p className="mt-2 text-xs text-red-600">超过 64 KiB 上限，请精简后保存。</p>
            )}
            <textarea
              className="mt-2 h-96 w-full resize-y rounded-2xl border border-stone-200 bg-white px-3 py-2 font-mono text-xs leading-5 text-stone-800 outline-none focus:border-amber-300"
              disabled={saving}
              onChange={(event) => setState((previous) => updateDraft(previous, event.target.value))}
              onKeyDown={handleTextareaKeyDown}
              ref={textareaRef}
              spellCheck={false}
              value={state.draft}
            />
            <div className="mt-4 flex items-center gap-3">
              <button
                className="flex-1 rounded-2xl bg-amber-600 px-4 py-3 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-40"
                disabled={!canSave}
                type="button"
                onClick={() => void handleSave()}
              >
                {saving ? "保存中…" : "保存"}
              </button>
              <button
                className="flex-1 rounded-2xl border border-stone-200 px-4 py-3 text-sm font-semibold text-stone-700 disabled:cursor-not-allowed disabled:opacity-40"
                disabled={saving}
                type="button"
                onClick={() => setState((previous) => cancelEditing(previous))}
              >
                取消
              </button>
              {savedFlash && <span className="text-xs font-medium text-amber-700">已保存</span>}
            </div>
          </div>
        )}

        {state.mode === "conflict" && (
          <div className="rounded-[2rem] border border-amber-200/80 bg-amber-50/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            <h2 className="text-sm font-semibold text-amber-900">内容已过期</h2>
            <p className="mt-1 text-xs leading-5 text-amber-800">
              记忆已被其他会话更新。重新加载会丢弃本地草稿并显示最新内容；也可以先复制下方草稿再重新加载。
            </p>
            {reloadError && <p className="mt-2 text-xs text-amber-800">{reloadError}</p>}
            <textarea
              className="mt-3 h-48 w-full resize-y rounded-2xl border border-amber-200 bg-white px-3 py-2 font-mono text-xs leading-5 text-stone-800"
              readOnly
              value={state.draft}
            />
            <div className="mt-4 flex items-center gap-3">
              <button
                className="flex-1 rounded-2xl bg-amber-600 px-4 py-3 text-sm font-semibold text-white"
                type="button"
                onClick={() => void handleConflictReload()}
              >
                重新加载最新内容
              </button>
              <button
                className="flex-1 rounded-2xl border border-amber-300 px-4 py-3 text-sm font-semibold text-amber-800"
                type="button"
                onClick={() => setState((previous) => conflictResumeEditing(previous))}
              >
                继续编辑草稿
              </button>
            </div>
          </div>
        )}
      </section>
    </main>
  );
}

"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { use, useCallback, useEffect, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  SUBAGENT_ERROR_CODES,
  SubagentDefinitionDetail,
  SubagentValidationIssue,
  SubagentVersionDetail,
  SubagentVersionSummary,
  deleteSubagentDefinition,
  extractSubagentError,
  getSubagentDefinition,
  getSubagentVersionDetail,
  listSubagentVersions,
  publishSubagentDefinition,
  restoreSubagentDefinition,
  saveSubagentDraft,
  setSubagentEnabled,
  validateSubagentMarkdown,
} from "@/lib/subagent-catalog";

export default function SubagentEditPage({ params }: { params: Promise<{ agentId: string }> }) {
  const { agentId } = use(params);
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [detail, setDetail] = useState<SubagentDefinitionDetail | null>(null);
  const [markdown, setMarkdown] = useState("");
  const [savedMarkdown, setSavedMarkdown] = useState("");
  const [revision, setRevision] = useState<number | null>(null);
  const [issues, setIssues] = useState<SubagentValidationIssue[]>([]);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [versions, setVersions] = useState<SubagentVersionSummary[]>([]);
  const [versionDetail, setVersionDetail] = useState<SubagentVersionDetail | null>(null);

  const isBuiltin = detail?.source === "BUILTIN";
  const dirty = markdown !== savedMarkdown;
  const deleted = detail?.deleted ?? false;
  const hasPublishedVersion = (detail?.currentVersion ?? null) !== null;

  const reload = useCallback(
    async (options: { keepLocalMarkdown?: boolean } = {}) => {
      const [loaded, loadedVersions] = await Promise.all([
        getSubagentDefinition(agentId),
        listSubagentVersions(agentId).catch(() => [] as SubagentVersionSummary[]),
      ]);
      setDetail(loaded);
      setVersions(loadedVersions);
      setRevision(loaded.draftRevision);
      if (!options.keepLocalMarkdown) {
        setMarkdown(loaded.draftMarkdown ?? loaded.currentMarkdown ?? "");
        setSavedMarkdown(loaded.draftMarkdown ?? loaded.currentMarkdown ?? "");
        setIssues(loaded.draftIssues ?? []);
      }
      setVersionDetail(null);
    },
    [agentId],
  );

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        try {
          await reload();
        } catch (loadError) {
          setError(loadError instanceof Error ? loadError.message : "加载定义失败");
        }
      })
      .catch(() => {
        savePostLoginRedirect(`/me/agents/subagents/${agentId}`);
        router.replace("/auth/login");
      });
  }, [agentId, reload, router]);

  function resetFeedback() {
    setMessage("");
    setError("");
  }

  async function handleSaveDraft() {
    if (busy || revision === null || isBuiltin || deleted) return;
    setBusy(true);
    resetFeedback();
    try {
      const result = await saveSubagentDraft(agentId, { expectedRevision: revision, markdown });
      setRevision(result.revision);
      setSavedMarkdown(markdown);
      setIssues(result.issues);
      setMessage(`已保存草稿 r${result.revision}`);
    } catch (saveError) {
      const { errorCode } = extractSubagentError(saveError);
      if (errorCode === SUBAGENT_ERROR_CODES.DRAFT_REVISION_CONFLICT) {
        // 设计 10.3：保留本地文本，提示服务器草稿已变化，不自动覆盖。
        setError("服务器草稿已变化：本地文本已保留，重新保存将基于最新 revision。");
        try {
          const latest = await getSubagentDefinition(agentId);
          setDetail(latest);
          setRevision(latest.draftRevision);
        } catch {
          // 刷新失败时保留当前 revision，用户可手动重试。
        }
      } else {
        setError(saveError instanceof Error ? saveError.message : "保存失败");
      }
    } finally {
      setBusy(false);
    }
  }

  async function handleValidate() {
    if (busy) return;
    setBusy(true);
    resetFeedback();
    try {
      const result = await validateSubagentMarkdown(markdown);
      setIssues(result.issues);
      setMessage(result.issues.length === 0 ? "校验通过" : "校验完成，见下方问题列表");
    } catch (validateError) {
      setError(validateError instanceof Error ? validateError.message : "校验失败");
    } finally {
      setBusy(false);
    }
  }

  async function handlePublish() {
    if (busy || revision === null || isBuiltin || deleted) return;
    if (dirty) {
      resetFeedback();
      setError("存在未保存修改：发布只针对已保存的当前 revision，请先保存草稿。");
      return;
    }
    setBusy(true);
    resetFeedback();
    try {
      const result = await publishSubagentDefinition(agentId, revision);
      setMessage(`已发布 v${result.version}（hash ${result.contentHash.slice(0, 8)}）`);
      await reload({ keepLocalMarkdown: true });
    } catch (publishError) {
      const { errorCode, issues: publishIssues } = extractSubagentError(publishError);
      if (errorCode === SUBAGENT_ERROR_CODES.PUBLISH_VALIDATION_FAILED && publishIssues.length > 0) {
        setIssues(publishIssues);
      }
      setError(publishError instanceof Error ? publishError.message : "发布失败");
    } finally {
      setBusy(false);
    }
  }

  async function handleToggleEnabled(nextEnabled: boolean) {
    if (busy || isBuiltin) return;
    setBusy(true);
    resetFeedback();
    try {
      const updated = await setSubagentEnabled(agentId, nextEnabled);
      setDetail(updated);
      setMessage(nextEnabled ? "已启用" : "已停用");
    } catch (enableError) {
      setError(enableError instanceof Error ? enableError.message : "操作失败");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (busy || isBuiltin) return;
    setBusy(true);
    resetFeedback();
    try {
      await deleteSubagentDefinition(agentId);
      await reload({ keepLocalMarkdown: true });
      setMessage("已删除（可恢复，未自动启用）");
    } catch (deleteError) {
      const { errorCode } = extractSubagentError(deleteError);
      if (errorCode === SUBAGENT_ERROR_CODES.DELETE_REQUIRES_DISABLED) {
        setError("删除前需先停用该 Subagent。");
      } else {
        setError(deleteError instanceof Error ? deleteError.message : "删除失败");
      }
    } finally {
      setBusy(false);
    }
  }

  async function handleRestore() {
    if (busy || isBuiltin) return;
    setBusy(true);
    resetFeedback();
    try {
      const restored = await restoreSubagentDefinition(agentId);
      setDetail(restored);
      setMessage("已恢复（恢复后保持停用，需手动启用）");
    } catch (restoreError) {
      setError(restoreError instanceof Error ? restoreError.message : "恢复失败");
    } finally {
      setBusy(false);
    }
  }

  async function handleOpenVersion(version: number) {
    if (busy) return;
    setBusy(true);
    resetFeedback();
    try {
      setVersionDetail(await getSubagentVersionDetail(agentId, version));
    } catch (openError) {
      setError(openError instanceof Error ? openError.message : "加载版本失败");
    } finally {
      setBusy(false);
    }
  }

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4">
        <header className="rounded-lg border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href="/me/agents">
            返回 Agent 管理
          </Link>
          <div className="mt-3 flex items-center justify-between gap-3">
            <h1 className="min-w-0 truncate font-mono text-xl font-semibold">{agentId}</h1>
            <span
              className={`shrink-0 rounded-md px-2 py-1 text-[11px] ${
                deleted
                  ? "bg-red-50 text-red-500"
                  : detail?.enabled
                    ? "bg-emerald-50 text-emerald-700"
                    : "bg-stone-100 text-stone-500"
              }`}
            >
              {isBuiltin ? "内置只读" : deleted ? "已删除" : detail?.enabled ? "已启用" : "未启用"}
            </span>
          </div>
          {detail ? (
            <p className="mt-2 text-xs text-stone-400">
              草稿 r{detail.draftRevision ?? "-"} · 当前 v{detail.currentVersion ?? "未发布"}
            </p>
          ) : null}
        </header>

        {message ? (
          <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{message}</p>
        ) : null}
        {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

        {isBuiltin ? (
          <section className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
            <h2 className="text-sm font-semibold text-stone-700">定义 Markdown（只读）</h2>
            <pre className="mt-3 max-h-96 overflow-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 font-mono text-xs leading-5 text-stone-700">
              {detail?.currentMarkdown ?? "加载中…"}
            </pre>
          </section>
        ) : (
          <>
            <section className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium text-stone-700" htmlFor="subagent-markdown">
                  定义 Markdown
                </label>
                {dirty ? (
                  <span className="rounded-md bg-amber-50 px-2 py-1 text-[11px] text-amber-700">未保存</span>
                ) : (
                  <span className="rounded-md bg-stone-50 px-2 py-1 text-[11px] text-stone-400">已保存</span>
                )}
              </div>
              <textarea
                id="subagent-markdown"
                className="mt-2 min-h-80 w-full rounded-lg border border-stone-200 bg-stone-50/60 px-3 py-3 font-mono text-xs leading-5 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                value={markdown}
                onChange={(event) => setMarkdown(event.target.value)}
                spellCheck={false}
                disabled={deleted}
              />
              <p className="mt-2 text-xs text-stone-400">
                共 {markdown.split("\n").length} 行 · 校验问题以行/列定位
              </p>

              <div className="mt-4 grid grid-cols-2 gap-2">
                <button
                  type="button"
                  className="rounded-lg bg-stone-900 px-3 py-2 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
                  disabled={busy || revision === null || deleted || !dirty}
                  onClick={() => void handleSaveDraft()}
                >
                  {busy ? "处理中" : "保存草稿"}
                </button>
                <button
                  type="button"
                  className="rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700 disabled:text-stone-300"
                  disabled={busy || deleted}
                  onClick={() => void handleValidate()}
                >
                  校验
                </button>
                <button
                  type="button"
                  className="rounded-lg bg-amber-600 px-3 py-2 text-sm font-semibold text-white transition hover:bg-amber-500 disabled:bg-stone-300"
                  disabled={busy || revision === null || deleted || dirty}
                  title={dirty ? "存在未保存修改，请先保存草稿" : undefined}
                  onClick={() => void handlePublish()}
                >
                  发布
                </button>
                <button
                  type="button"
                  className="rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700 disabled:text-stone-300"
                  disabled={busy || deleted || !hasPublishedVersion}
                  title={!hasPublishedVersion ? "需要先发布一个版本" : undefined}
                  onClick={() => void handleToggleEnabled(!detail?.enabled)}
                >
                  {detail?.enabled ? "停用" : "启用"}
                </button>
                {deleted ? (
                  <button
                    type="button"
                    className="col-span-2 rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700"
                    disabled={busy}
                    onClick={() => void handleRestore()}
                  >
                    恢复
                  </button>
                ) : (
                  <button
                    type="button"
                    className="col-span-2 rounded-lg border border-red-200 px-3 py-2 text-sm font-semibold text-red-600 disabled:border-stone-100 disabled:text-stone-300"
                    disabled={busy || detail?.enabled}
                    title={detail?.enabled ? "删除前需先停用该 Subagent" : undefined}
                    onClick={() => void handleDelete()}
                  >
                    删除
                  </button>
                )}
              </div>
              {detail?.enabled ? (
                <p className="mt-2 text-xs text-stone-400">启用中的定义不可删除，需先停用。</p>
              ) : null}
            </section>

            <section className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <h2 className="text-sm font-semibold text-stone-700">校验结果</h2>
              {issues.length === 0 ? (
                <p className="mt-2 text-sm text-stone-400">暂无校验问题。</p>
              ) : (
                <ul className="mt-2 space-y-2">
                  {issues.map((issue, index) => (
                    <li
                      key={`${issue.code}-${issue.line ?? 0}-${index}`}
                      className={`rounded-lg px-3 py-2 text-xs leading-5 ${
                        issue.severity === "ERROR"
                          ? "bg-red-50 text-red-600"
                          : "bg-amber-50 text-amber-700"
                      }`}
                    >
                      <span className="font-semibold">{issue.severity ?? "INFO"}</span>
                      {issue.field ? <span> · {issue.field}</span> : null}
                      {issue.line !== null && issue.line !== undefined ? (
                        <span> · 行 {issue.line}{issue.column ? `:${issue.column}` : ""}</span>
                      ) : null}
                      <span> · {issue.code}</span>
                      <p className="mt-1 text-stone-600">{issue.message}</p>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm">
              <h2 className="text-sm font-semibold text-stone-700">发布版本</h2>
              {versions.length === 0 ? (
                <p className="mt-2 text-sm text-stone-400">尚未发布任何版本。</p>
              ) : (
                <div className="mt-2 space-y-2">
                  {versions.map((version) => (
                    <button
                      key={version.version}
                      type="button"
                      className="flex w-full items-center justify-between gap-3 rounded-lg border border-stone-200 px-3 py-2 text-left text-xs transition hover:border-amber-400 disabled:opacity-60"
                      disabled={busy}
                      onClick={() => void handleOpenVersion(version.version)}
                    >
                      <span className="font-mono text-stone-700">
                        v{version.version} · {version.contentHash.slice(0, 8)}
                      </span>
                      <span className="text-stone-400">
                        {version.current ? "当前 · " : ""}
                        {version.publishedAt ? new Date(version.publishedAt).toLocaleString() : ""}
                      </span>
                    </button>
                  ))}
                </div>
              )}

              {versionDetail ? (
                <div className="mt-3 space-y-2">
                  <h3 className="text-xs font-semibold text-stone-600">
                    v{versionDetail.version} Markdown（只读）
                    {versionDetail.current ? " · 当前" : ""}
                  </h3>
                  <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-lg bg-stone-50 p-3 font-mono text-xs leading-5 text-stone-700">
                    {versionDetail.markdown}
                  </pre>
                  <button
                    type="button"
                    className="w-full rounded-lg border border-stone-200 px-3 py-2 text-sm font-semibold text-stone-700"
                    onClick={() => setVersionDetail(null)}
                  >
                    收起
                  </button>
                </div>
              ) : null}
            </section>
          </>
        )}
      </section>
    </main>
  );
}

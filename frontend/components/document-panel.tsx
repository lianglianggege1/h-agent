"use client";

import { useEffect, useState } from "react";
import { useFilePreview } from "./file-preview";
import {
  documentTasks,
  reviewDocument,
  decideDocument,
  documentAction,
  type DocumentTask,
  type DocumentDecision,
} from "@/lib/documents";

const labels: Record<string, string> = {
  PARSING: "本地解析中",
  GOOD: "识别完成",
  RECOGNIZED: "识别完成（未复核）",
  LOW: "需要 OCR",
  EMPTY: "未提取到正文",
  NEEDS_EXTERNAL_CONSENT: "等待自动识别",
  CLOUD_UNAVAILABLE: "外部识别尚未就绪",
  QUEUED: "等待识别",
  OCR_RUNNING: "识别中",
  VERIFYING: "正在复核",
  VERIFIED: "复核通过",
  WAITING_CONFIRMATION: "请核对低分内容",
  VERIFICATION_FAILED: "有内容未完成复核",
  CONFIRMED: "人工确认完成",
  FAILED: "处理失败",
  ENCRYPTED: "需要解密",
  UNSUPPORTED: "需要转换格式或人工文本",
  BUDGET_EXHAUSTED: "已达到处理时间上限",
  CANCELLED: "已取消",
  SUPERSEDED: "已有新识别版本",
  GENERATING: "文件生成与检查中",
  COMPLETED: "文件已交付",
};

function ReviewCard({
  session,
  task,
  onChanged,
}: {
  session: string;
  task: DocumentTask;
  onChanged: () => void;
}) {
  const preview = useFilePreview();
  const [review, setReview] = useState<DocumentTask | null>(null);
  const [decisions, setDecisions] = useState<Record<string, DocumentDecision>>(
    {},
  );
  const [hint, setHint] = useState("");
  const [allowUnchanged, setAllowUnchanged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const pending = task.status === "WAITING_CONFIRMATION";
  const canCancel =
    !task.continuationCancelled &&
    ["PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING", "GENERATING"].includes(
      task.status,
    );
  useEffect(() => {
    let disposed = false;
    if (!pending) return;
    void reviewDocument(session, task.taskId)
      .then((value) => {
        if (!disposed) {
          setReview(value);
          setDecisions({});
        }
      })
      .catch((failure) => {
        if (!disposed)
          setError(
            failure instanceof Error ? failure.message : "复核内容加载失败",
          );
      });
    return () => {
      disposed = true;
    };
  }, [session, task.taskId, task.revision, pending]);

  async function perform(action: string) {
    setBusy(true);
    setError("");
    try {
      if (action === "DECIDE") {
        if (!review) return;
        setReview(
          await decideDocument(session, review, Object.values(decisions)),
        );
        setDecisions({});
      } else await documentAction(session, task, action, hint, allowUnchanged);
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "文档操作失败");
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="rounded-xl border border-stone-200 bg-white p-3 text-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-medium" title={task.fileName}>
            {task.kind === "REVIEW" ? "质量复核 · " : ""}{task.fileName}
          </p>
          <p className="mt-1 text-xs text-stone-600">
            {labels[task.status] ?? task.status}
            {task.totalPages > 0
              ? ` · 第 ${task.startPage}–${task.endPage} 页 / 共 ${task.totalPages} 页`
              : ""}
          </p>
        </div>
        {task.downloadUrl && task.outputResourceId && task.status === "COMPLETED" ? <button type="button" className="shrink-0 underline" onClick={() => preview?.open([{
          id: task.outputResourceId!, fileName: task.fileName, mimeType: "application/octet-stream", fileSize: null,
          viewUrl: `/api/chat/resources/${encodeURIComponent(task.outputResourceId!)}/content`, downloadUrl: task.downloadUrl!,
        }], task.outputResourceId!)}>预览文件</button> : null}
        {task.downloadUrl ? (
          <a href={task.downloadUrl} className="shrink-0 underline">
            下载文件
          </a>
        ) : null}
      </div>
      {task.detail ? (
        <p className="mt-2 text-xs text-stone-600">{task.detail}</p>
      ) : null}
      {task.resumeStatus === "NEEDS_RESUME" ? (
        <p className="mt-2 text-xs text-amber-700">
          识别已完成，但自动续跑未完成；可以继续原请求。
        </p>
      ) : task.resumeStatus === "QUEUED" || task.resumeStatus === "RUNNING" ? (
        <p className="mt-2 text-xs text-blue-700">
          识别已完成，正在继续回答原问题。
        </p>
      ) : null}
      {task.continuationCancelled ? (
        <p className="mt-2 text-xs text-stone-500">
          原请求已取消，确认仅解锁内容。
        </p>
      ) : null}
      {review && pending ? (
        <div className="mt-3 space-y-4">
          {review.pages?.map((page) => (
            <section key={page.number} className="space-y-2">
              <p className="font-medium">
                第 {page.number} 页 {page.diagnostic}
              </p>
              {page.blocks.map((block) => (
                <div
                  key={block.id}
                  className="rounded-lg border border-stone-200 p-2"
                >
                  <p className="mb-2 text-xs text-stone-600">
                    {block.canAccept
                      ? `复核分数 ${block.score.toFixed(3)}`
                      : block.verdict === "COVERAGE_ERROR"
                        ? "可能存在遗漏，请核对并填写这一页的完整文本"
                        : "复核未完成，请人工修正或重新识别"}
                  </p>
                  <div className="grid gap-3 md:grid-cols-2">
                    <a
                      href={block.imageUrl}
                      target="_blank"
                      rel="noreferrer"
                      aria-label={`查看第 ${page.number} 页原图区域`}
                    >
                      <picture>
                        <img
                          src={block.imageUrl}
                          alt={`第 ${page.number} 页待核对区域`}
                          className="max-h-72 w-full rounded bg-stone-50 object-contain"
                        />
                      </picture>
                    </a>
                    <label className="block text-xs">
                      识别文本 / 人工修正
                      <textarea
                        className="mt-1 min-h-36 w-full rounded border border-stone-300 p-2 text-sm"
                        value={decisions[block.id]?.text ?? block.text}
                        maxLength={40000}
                        onChange={(event) =>
                          setDecisions((current) => ({
                            ...current,
                            [block.id]: {
                              blockId: block.id,
                              action: "CORRECT",
                              text: event.target.value,
                            },
                          }))
                        }
                      />
                    </label>
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {block.canAccept ? (
                      <button
                        type="button"
                        className="agui-button"
                        disabled={busy}
                        aria-pressed={decisions[block.id]?.action === "ACCEPT"}
                        onClick={() =>
                          setDecisions((current) => ({
                            ...current,
                            [block.id]: { blockId: block.id, action: "ACCEPT" },
                          }))
                        }
                      >
                        接受原识别
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="agui-button"
                      disabled={busy}
                      aria-pressed={decisions[block.id]?.action === "CORRECT"}
                      onClick={() =>
                        setDecisions((current) => ({
                          ...current,
                          [block.id]: {
                            blockId: block.id,
                            action: "CORRECT",
                            text: current[block.id]?.text ?? block.text,
                          },
                        }))
                      }
                    >
                      提交为人工修正
                    </button>
                    {decisions[block.id] ? (
                      <span className="self-center text-xs text-emerald-700">
                        已选择
                        {decisions[block.id].action === "ACCEPT"
                          ? "接受原识别"
                          : "人工修正"}
                      </span>
                    ) : null}
                  </div>
                </div>
              ))}
            </section>
          ))}
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="agui-button"
              disabled={busy}
              onClick={() => {
                const accepted =
                  review.pages
                    ?.flatMap((page) => page.blocks)
                    .filter((block) => block.canAccept)
                    .map((block) => [
                      block.id,
                      { blockId: block.id, action: "ACCEPT" as const },
                    ]) ?? [];
                setDecisions((current) => ({
                  ...Object.fromEntries(accepted),
                  ...current,
                }));
              }}
            >
              选择接受全部低分块
            </button>
            <button
              type="button"
              className="agui-button agui-button-primary"
              disabled={busy || !Object.keys(decisions).length}
              onClick={() => void perform("DECIDE")}
            >
              保存核对结果并继续
            </button>
          </div>
        </div>
      ) : null}
      {[
        "WAITING_CONFIRMATION",
        "VERIFICATION_FAILED",
        "FAILED",
        "BUDGET_EXHAUSTED",
      ].includes(task.status) && task.kind === "PARSE" ? (
        <details className="mt-3">
          <summary className="cursor-pointer text-xs underline">
            重新识别问题页
          </summary>
          <p className="mt-2 text-xs text-stone-600">
            当前托管接口按原设置重跑，新增提示可用于说明问题。
          </p>
          <label className="mt-2 block text-xs">
            问题说明
            <input
              className="mt-1 w-full rounded border border-stone-300 p-2"
              value={hint}
              onChange={(event) => setHint(event.target.value)}
            />
          </label>
          <label className="mt-2 flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={allowUnchanged}
              onChange={(event) => setAllowUnchanged(event.target.checked)}
            />
            同意按原设置重新识别
          </label>
          <button
            type="button"
            className="agui-button mt-2"
            disabled={busy || !allowUnchanged}
            onClick={() => void perform("REOCR")}
          >
            重新识别并复核
          </button>
        </details>
      ) : null}
      <div className="mt-2 flex flex-wrap gap-2">
        {["BUDGET_EXHAUSTED", "FAILED"].includes(task.status) ? (
          <button
            type="button"
            className="agui-button"
            disabled={busy}
            onClick={() => void perform("CONTINUE")}
          >
            保留进度继续处理
          </button>
        ) : null}
        {task.resumeStatus === "NEEDS_RESUME" &&
        task.released &&
        !task.continuationCancelled ? (
          <button
            type="button"
            className="agui-button"
            disabled={busy}
            onClick={() => void perform("RESUME")}
          >
            继续原请求
          </button>
        ) : null}
        {canCancel ? (
          <button
            type="button"
            className="agui-button"
            disabled={busy}
            onClick={() => void perform("CANCEL")}
          >
            取消原任务
          </button>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="mt-2 text-xs text-red-700">
          {error}
        </p>
      ) : null}
    </article>
  );
}

/** Background synchronization has no visible container; cards belong to their original chat turn. */
export function DocumentTaskSync({
  session,
  chatRunning,
  onUpdated,
}: {
  session: string;
  chatRunning: boolean;
  onUpdated: (tasks: DocumentTask[]) => Promise<void>;
}) {
  useEffect(() => {
    let disposed = false;
    let polling = false;
    const load = async () => {
      if (polling || document.visibilityState === "hidden") return;
      polling = true;
      try {
        const tasks = await documentTasks(session);
        if (!disposed) await onUpdated(tasks);
      } catch {
        // A transient polling failure preserves the last card; the next poll retries.
      } finally {
        polling = false;
      }
    };
    void load();
    const timer = window.setInterval(() => void load(), 5000);
    document.addEventListener("visibilitychange", load);
    window.addEventListener("documents:changed", load);
    return () => {
      disposed = true;
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", load);
      window.removeEventListener("documents:changed", load);
    };
  }, [session, chatRunning, onUpdated]);
  return null;
}

/** Only a Jev low score opens manual review; technical failures show recovery actions. */
export function DocumentTaskCard({
  content,
}: {
  content: { task: DocumentTask };
}) {
  return (
    <ReviewCard
      session={content.task.sessionId}
      task={content.task}
      onChanged={() => window.dispatchEvent(new Event("documents:changed"))}
    />
  );
}

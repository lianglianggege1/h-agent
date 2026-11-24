"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { DocumentTaskCard } from "@/components/document-panel";
import { reviewDocument, type DocumentTask } from "@/lib/documents";
import { apiFetch } from "@/lib/http";

type Task = {
  kind: "DOCUMENT" | "VIDEO" | "AUTOMATION";
  id: string;
  parentId: string | null;
  sessionId: string | null;
  title: string;
  status: string;
  createdAt: string;
  output: string | null;
  failure: string | null;
  resourceId: string | null;
  canCancel: boolean;
};

const kinds = { DOCUMENT: "文档", VIDEO: "视频", AUTOMATION: "自动化" };
const states: Record<string, string> = {
  PARSING: "解析中", PENDING_SUBMISSION: "已受理", QUEUED: "排队中", IN_PROGRESS: "执行中",
  WORKING: "执行中", RUNNING: "执行中", MATERIALIZING: "交付中", RETRY_WAIT: "等待重试",
  WAITING_CONFIRMATION: "待确认", VERIFICATION_FAILED: "待复核", CONFIRMED: "已确认",
  GOOD: "已解析", RECOGNIZED: "已识别", GENERATED: "已交付", COMPLETED: "已完成",
  SUCCEEDED: "已完成", FAILED: "失败", UNSUPPORTED: "格式不支持",
  BUDGET_EXHAUSTED: "超出处理预算", CANCEL_REQUESTED: "取消中", CANCELLED: "已取消",
  SUPERSEDED: "已替换", TIMED_OUT: "超时",
};

export default function PersonalTasksPage() {
  const [items, setItems] = useState<Task[]>([]);
  const [kind, setKind] = useState<Task["kind"] | "ALL">("ALL");
  const [document, setDocument] = useState<DocumentTask | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");

  const refresh = useCallback(async (signal?: AbortSignal) => {
    try {
      const snapshot = await apiFetch<Task[]>("/api/workspace/tasks", { signal });
      if (!signal?.aborted) {
        setItems(snapshot);
        setError("");
      }
    } catch (failure) {
      if (!signal?.aborted) setError(failure instanceof Error ? failure.message : "任务加载失败");
    } finally {
      if (!signal?.aborted) setLoaded(true);
    }
  }, []);

  useEffect(() => {
    const lifetime = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function observe() {
      await refresh(lifetime.signal);
      if (!lifetime.signal.aborted) timer = setTimeout(observe, 4000);
    }
    void observe();
    return () => {
      lifetime.abort();
      clearTimeout(timer);
    };
  }, [refresh]);

  useEffect(() => {
    function updateDocument() {
      void refresh();
      if (document) void reviewDocument(document.sessionId, document.taskId)
        .then(setDocument).catch(() => setDocument(null));
    }
    window.addEventListener("documents:changed", updateDocument);
    return () => window.removeEventListener("documents:changed", updateDocument);
  }, [document, refresh]);

  async function action(task: Task, mode: "document" | "cancel") {
    setBusy(true);
    setError("");
    setFeedback("");
    try {
      if (mode === "document" && task.sessionId) {
        setDocument(await reviewDocument(task.sessionId, task.id));
      } else if (mode === "cancel" && task.kind === "AUTOMATION") {
        await apiFetch("/api/automations/runs/" + encodeURIComponent(task.id) + "/cancel", { method: "POST" });
        await refresh();
      } else if (mode === "cancel" && task.kind === "VIDEO") {
        const result = await apiFetch<{ message: string }>("/api/workspace/tasks/videos/" + encodeURIComponent(task.id) + "/cancel",
          { method: "POST" });
        setFeedback(result.message);
        await refresh();
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "任务操作失败");
    } finally {
      setBusy(false);
    }
  }

  const visible = items.filter((task) => kind === "ALL" || task.kind === kind);
  return <main className="mx-auto max-w-5xl p-6 sm:p-8">
    <header className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <h1 className="text-2xl font-semibold tracking-tight">个人任务</h1>
      <label className="flex items-center gap-2 text-sm">
        任务类型
        <select className="rounded-lg border border-stone-200 bg-white px-3 py-2" value={kind}
          onChange={(event) => setKind(event.target.value as Task["kind"] | "ALL")}>
          <option value="ALL">全部</option>
          {Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
    </header>
    {error ? <p role="alert" className="mb-4 text-sm text-red-700">{error}</p> : null}
    {feedback ? <p role="status" className="mb-4 text-sm">{feedback}</p> : null}
    {document ? <section className="mb-5 rounded-xl border border-stone-200 p-4">
      <button className="agui-button mb-3" type="button" onClick={() => setDocument(null)}>关闭文档详情</button>
      <DocumentTaskCard key={document.taskId} content={{ task: document }} />
    </section> : null}
    {!visible.length && !error ? <p className="text-sm text-stone-500">{loaded ? "暂无任务" : "正在加载任务…"}</p> : null}
    <ul className="divide-y divide-stone-200 rounded-xl border border-stone-200">
      {visible.map((task) => <li key={task.kind + ":" + task.id} className="flex flex-wrap justify-between gap-4 p-4">
        <div className="min-w-0 flex-1">
          <p className="text-xs text-stone-500">
            {kinds[task.kind]} · 创建于 <time dateTime={task.createdAt}>{new Date(task.createdAt).toLocaleString("zh-CN")}</time>
          </p>
          <h2 className="mt-1 break-words text-sm font-semibold">{task.title}</h2>
          <p className="mt-1 text-sm">{states[task.status] ?? task.status}</p>
          {task.failure ? <p className="mt-1 break-words text-sm text-red-700">{task.failure}</p> : null}
          {task.output ? <p className="mt-1 whitespace-pre-wrap break-words text-sm text-stone-600">{task.output}</p> : null}
        </div>
        <div className="flex flex-wrap items-start gap-2">
          {task.sessionId ? <Link className="agui-button" href={"/chat?sessionId=" + encodeURIComponent(task.sessionId)}>查看原会话</Link> : null}
          {task.resourceId ? <a className="agui-button" target="_blank" rel="noreferrer"
            href={"/api/chat/resources/" + encodeURIComponent(task.resourceId) + "/content"}>打开视频</a> : null}
          {task.kind === "DOCUMENT" && task.sessionId ? <button className="agui-button" disabled={busy} onClick={() => void action(task, "document")}>查看文档</button> : null}
          {task.canCancel ?
            <button className="agui-button" disabled={busy} onClick={() => void action(task, "cancel")}>取消运行</button> : null}
        </div>
      </li>)}
    </ul>
  </main>;
}

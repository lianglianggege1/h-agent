"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/http";
import { documentTasks, type DocumentTask } from "@/lib/documents";
import { DocumentTaskCard } from "./document-panel";

type Canvas = { revision: number; title: string | null; content: string; sourceTaskId: string | null; sourceResourceId: string | null };

type CanvasAction = "save" | "import" | "continue" | "export" | "title";

export function DocumentCanvas({ session, sourceTaskId, onClose, onReference }: {
  session: string; sourceTaskId?: string; onClose: () => void; onReference: (text: string) => void;
}) {
  const root = "/api/chat/documents/sessions/" + encodeURIComponent(session) + "/canvas";
  const editor = useRef<HTMLTextAreaElement>(null);
  const [canvas, setCanvas] = useState<Canvas | null>(null);
  const [text, setText] = useState("");
  const [tasks, setTasks] = useState<DocumentTask[]>([]);
  const [selection, setSelection] = useState({ sourceTaskId, taskId: sourceTaskId ?? "" });
  const selected = selection.sourceTaskId === sourceTaskId ? selection.taskId : sourceTaskId ?? "";
  const [instruction, setInstruction] = useState("");
  const [page, setPage] = useState(1);
  const [pendingAction, setPendingAction] = useState<CanvasAction | null>(null);
  const busy = pendingAction !== null;
  const [status, setStatus] = useState("");
  const [error, setError] = useState("");
  const [latest, setLatest] = useState<Canvas | null>(null);

  useEffect(() => {
    const lifetime = new AbortController();
    void apiFetch<Canvas>(root, { signal: lifetime.signal }).then((value) => {
      if (!lifetime.signal.aborted) { setCanvas(value); setText(value.content); }
    }).catch((failure) => {
      if (!lifetime.signal.aborted) setError(failure instanceof Error ? failure.message : "画布加载失败");
    });
    return () => lifetime.abort();
  }, [root]);

  useEffect(() => {
    let current = true;
    async function refresh() {
      try {
        const updated = await documentTasks(session);
        if (current) setTasks(updated.filter((task) => task.kind === "PARSE" || task.kind === "REVIEW"));
      } catch { /* The chat still owns the user's document upload and status. */ }
    }
    void refresh();
    const timer = setInterval(() => void refresh(), 5000);
    window.addEventListener("documents:changed", refresh);
    return () => { current = false; clearInterval(timer); window.removeEventListener("documents:changed", refresh); };
  }, [session]);

  async function act(action: CanvasAction) {
    if (!canvas || busy) return;
    setPendingAction(action); setError(""); setStatus("");
    try {
      let current = canvas;
      if (action !== "import" && text !== canvas.content) {
        current = await apiFetch<Canvas>(root, { method: "PUT",
          body: JSON.stringify({ revision: canvas.revision, content: text }) });
        setCanvas(current);
      }
      if (action === "save") {
        setStatus("已保存，接下来可以在对话里问这份文稿。");
      } else if (action === "title") {
        const named = await apiFetch<Canvas>(root + "/title", { method: "POST",
          body: JSON.stringify({ revision: current.revision }) });
        setCanvas(named); setLatest(null);
        setStatus("标题已保存。");
      } else if (action === "export") {
        await apiFetch(root + "/exports", { method: "POST",
          body: JSON.stringify({ revision: current.revision, format: "docx" }) });
        window.dispatchEvent(new Event("documents:changed"));
        setStatus("导出已提交，完成后发送到会话。");
      } else {
        const path = action === "import" ? "/import" : "/continue";
        const next = await apiFetch<Canvas>(root + path, { method: "POST",
          body: JSON.stringify({ revision: current.revision, taskId: selected, instruction }) });
        setCanvas(next); setText(next.content); setLatest(null);
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "画布操作失败");
      const remote = await apiFetch<Canvas>(root).catch(() => null);
      if (remote && remote.revision !== canvas.revision) setLatest(remote);
    } finally { setPendingAction(null); }
  }

  const sourceTask = tasks.find((task) => task.taskId === selected);
  const source = canvas?.sourceResourceId ?? sourceTask?.resourceId;
  return <section className="pointer-events-auto mx-auto mb-2 flex max-h-[min(50dvh,34rem)] w-full max-w-[968px] flex-col rounded-xl border border-stone-200 bg-white px-3 py-2 sm:px-4" aria-label="对话中的文档画布">
    <header className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <h2 className="truncate text-sm font-semibold">{canvas?.title ?? "未命名文稿"}</h2>
        {canvas && canvas.revision > 0 ? <p className="truncate text-xs text-stone-500">已保存 v{canvas.revision}{text !== canvas.content ? " · 有未保存的修改" : ""}</p> : null}
      </div>
      <div className="flex shrink-0 gap-2">
        <button type="button" className="agui-button" disabled={busy || !canvas?.content.trim() || text !== canvas.content}
          onClick={() => void act("title")}>{pendingAction === "title" ? "起名中…" : "AI 起名"}</button>
        <button type="button" className="agui-button" onClick={onClose}>收起</button>
      </div>
    </header>
    <div className="min-h-0 space-y-3 overflow-y-auto pt-3">
      {error ? <p role="alert" className="text-sm text-red-700">{error}</p> : null}
      {status ? <p role="status" className="text-sm text-stone-700">{status}</p> : null}
      {latest ? <details className="rounded-lg border border-amber-200 p-2 text-sm">
        <summary>对比最新版本 v{latest.revision}</summary>
        <p className="whitespace-pre-wrap p-2">{latest.content}</p>
        <button type="button" className="agui-button" onClick={() => { setCanvas(latest); setText(latest.content); setLatest(null); }}>
          使用最新版本
        </button>
      </details> : null}
      {tasks.length ? <div className="flex flex-wrap gap-2">
        <select aria-label="选择原文解析任务" className="min-w-0 flex-1 rounded border border-stone-200 p-2 text-sm"
          value={selected} onChange={(event) => setSelection({ sourceTaskId, taskId: event.target.value })}>
          <option value="">选择原文解析任务</option>
          {tasks.map((task) => <option key={task.taskId} value={task.taskId}>{task.fileName} · {task.kind === "REVIEW" ? "质量复核" : "原始识别"}{task.released ? "" : "（待完成）"}</option>)}
        </select>
        <button type="button" className="agui-button" disabled={busy || !sourceTask?.released || !!text}
          onClick={() => void act("import")}>导入到空白画布</button>
      </div> : null}
      {sourceTask && !sourceTask.released ? <DocumentTaskCard key={sourceTask.taskId} content={{ task: sourceTask }} /> : null}
      {source ? <div className="flex items-center gap-2 text-sm">
        <label>原文页码 <input value={page} min={1} type="number" className="w-16 rounded border border-stone-200 px-1"
          onChange={(event) => setPage(Math.max(1, Number(event.target.value) || 1))} /></label>
        <a className="underline" target="_blank" rel="noreferrer"
          href={"/api/chat/resources/" + encodeURIComponent(source) + "/content#page=" + page}>打开原文</a>
      </div> : null}
      <textarea ref={editor} aria-label="画布正文" className="min-h-28 w-full resize-y rounded-xl border border-stone-200 p-3 text-sm leading-6"
        value={text} onChange={(event) => setText(event.target.value)} disabled={!canvas || busy} placeholder="输入正文或导入已经复核的原文" />
      <div className="flex flex-wrap gap-2">
        <button type="button" className="agui-button agui-button-primary" disabled={busy || !canvas || text === canvas.content}
          onClick={() => void act("save")}>保存</button>
        <button type="button" className="agui-button" disabled={busy || !canvas || !text.trim()}
          onClick={() => void act("export")}>导出到会话</button>
        <button type="button" className="agui-button" disabled={!text.trim()} onClick={() => {
          const area = editor.current;
          const chosen = area ? text.slice(area.selectionStart, area.selectionEnd).trim() : "";
          if (!chosen || chosen.length > 1600) { setError("请先选择 1600 字以内的片段"); return; }
          const taskId = canvas?.sourceTaskId ?? sourceTask?.taskId;
          const location = taskId ? `原文任务 ${taskId}，第 ${page} 页\n` : "";
          onReference(`${location}画布 v${canvas?.revision ?? 0}\n${chosen}`);
        }}>引用所选</button>
      </div>
      <label className="block text-sm">续写要求
        <textarea value={instruction} onChange={(event) => setInstruction(event.target.value)}
          className="mt-1 w-full rounded border border-stone-200 p-2" rows={2} placeholder="例如，为当前文稿补一段结论" />
      </label>
      <button type="button" className="agui-button" disabled={busy || !canvas || !instruction.trim()}
        onClick={() => void act("continue")}>让 Agent 续写当前稿</button>
    </div>
  </section>;
}

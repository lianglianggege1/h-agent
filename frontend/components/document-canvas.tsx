"use client";

import { useEffect, useRef, useState } from "react";
import { apiFetch } from "@/lib/http";
import { documentTasks, type DocumentTask } from "@/lib/documents";
import { DocumentTaskCard } from "./document-panel";

type Canvas = { revision: number; content: string; sourceTaskId: string | null; sourceResourceId: string | null };

export function DocumentCanvas({ session, onClose, onReference }: {
  session: string; onClose: () => void; onReference: (text: string) => void;
}) {
  const root = "/api/chat/documents/sessions/" + encodeURIComponent(session) + "/canvas";
  const editor = useRef<HTMLTextAreaElement>(null);
  const [canvas, setCanvas] = useState<Canvas | null>(null);
  const [text, setText] = useState("");
  const [tasks, setTasks] = useState<DocumentTask[]>([]);
  const [selected, setSelected] = useState("");
  const [instruction, setInstruction] = useState("");
  const [page, setPage] = useState(1);
  const [busy, setBusy] = useState(false);
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
        if (current) setTasks(updated.filter((task) => task.kind === "PARSE"));
      } catch { /* The chat still owns the user's document upload and status. */ }
    }
    void refresh();
    const timer = setInterval(() => void refresh(), 5000);
    window.addEventListener("documents:changed", refresh);
    return () => { current = false; clearInterval(timer); window.removeEventListener("documents:changed", refresh); };
  }, [session]);

  async function act(action: "save" | "import" | "continue" | "export") {
    if (!canvas || busy) return;
    setBusy(true); setError(""); setStatus("");
    try {
      let current = canvas;
      if (action !== "import" && text !== canvas.content) {
        current = await apiFetch<Canvas>(root, { method: "PUT",
          body: JSON.stringify({ revision: canvas.revision, content: text }) });
        setCanvas(current);
      }
      if (action === "save") {
        setStatus("已保存");
      } else if (action === "export") {
        await apiFetch(root + "/exports", { method: "POST",
          body: JSON.stringify({ revision: current.revision, format: "docx", name: "会话文稿" }) });
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
    } finally { setBusy(false); }
  }

  const sourceTask = tasks.find((task) => task.taskId === selected);
  const source = canvas?.sourceResourceId ?? sourceTask?.resourceId;
  return <section className="fixed inset-y-0 right-0 z-30 flex w-full max-w-xl flex-col border-l border-stone-200 bg-white p-4 shadow-xl sm:p-5" aria-label="文档画布">
    <header className="flex items-center justify-between gap-4">
      <h2 className="text-lg font-semibold">文档画布 {canvas ? <span className="text-xs text-stone-500">v{canvas.revision}</span> : null}</h2>
      <button type="button" className="agui-button" onClick={onClose}>关闭</button>
    </header>
    <div className="min-h-0 flex-1 space-y-3 overflow-y-auto pt-4">
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
          value={selected} onChange={(event) => setSelected(event.target.value)}>
          <option value="">选择原文解析任务</option>
          {tasks.map((task) => <option key={task.taskId} value={task.taskId}>{task.fileName} · {task.status}</option>)}
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
      <textarea ref={editor} aria-label="画布正文" className="min-h-56 w-full rounded-xl border border-stone-200 p-3 text-sm leading-6"
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

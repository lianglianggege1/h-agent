"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { apiFetch } from "@/lib/http";

type Change = { index: number; original: string; replacement: string };
type Draft = { id: string; version: number; changes: Change[]; sources: string[] | null };
type Editor = {
  current: { title: string; version: number; body: string; applicability: string;
    sources: string[]; history: { version: number; title: string }[]; paragraphs: string[] };
  draft: Draft | null;
};

export default function KnowledgeEditorPage() {
  const { docId } = useParams<{ docId: string }>();
  const root = "/api/knowledge/documents/" + encodeURIComponent(docId);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [instruction, setInstruction] = useState("");
  const [targets, setTargets] = useState<number[]>([]);
  const [additionalSources, setAdditionalSources] = useState("");
  const [selected, setSelected] = useState<number[]>([]);
  const [edits, setEdits] = useState<Record<number, string>>({});
  const [source, setSource] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState("");

  useEffect(() => {
    const lifetime = new AbortController();
    void apiFetch<Editor>(root + "/editor", { signal: lifetime.signal }).then((value) => {
      if (!lifetime.signal.aborted) setEditor(value);
    }).catch((failure) => {
      if (!lifetime.signal.aborted) setError(failure instanceof Error ? failure.message : "条目读取失败");
    });
    return () => lifetime.abort();
  }, [root]);

  async function update(action: "propose" | "commit") {
    if (!editor || busy) return;
    setBusy(true); setError(""); setSaved("");
    try {
      if (action === "propose") {
        const sourceRefs = additionalSources.split("\n").map((ref) => ref.trim()).filter(Boolean);
        const draft = await apiFetch<Draft>(root + "/drafts", { method: "POST",
          body: JSON.stringify({ instruction, paragraphs: targets, sourceRefs }) });
        setEditor({ ...editor, draft });
        setSelected([]); setEdits({});
      } else if (editor.draft) {
        const changes = editor.draft.changes.filter((change) => selected.includes(change.index)).map((change) =>
          ({ index: change.index, replacement: edits[change.index] ?? change.replacement }));
        const result = await apiFetch<{ status: string }>(root + "/drafts/" + encodeURIComponent(editor.draft.id) + "/apply",
          { method: "POST", body: JSON.stringify({ selections: changes }) });
        if (result.status !== "COMPLETED") {
          if (result.status === "PENDING") setSaved("修改正在索引，可稍后刷新查看。");
          else setError("未能提交修改（" + result.status + "），请核对条目当前版本。");
        } else {
          setEditor(await apiFetch<Editor>(root + "/editor"));
          setSaved("所选修改已保存。");
          setSelected([]); setEdits({});
        }
      }
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : "操作失败");
      if (action === "commit") {
        const current = await apiFetch<Editor>(root + "/editor").catch(() => null);
        if (current) setEditor({ ...editor, current: current.current });
      }
    } finally { setBusy(false); }
  }

  const stale = editor?.draft && editor.draft.version !== editor.current.version;
  const references = editor?.draft?.sources ?? editor?.current.sources ?? [];
  return <main className="mx-auto max-w-4xl space-y-4 p-6 sm:p-8">
    <Link href="/me/knowledge" className="text-sm underline">返回知识库</Link>
    <h1 className="text-2xl font-semibold">{editor?.current.title ?? "修订知识条目"}</h1>
    {error ? <p role="alert" className="text-red-700">{error}</p> : null}
    {saved ? <p role="status">{saved}</p> : null}
    {editor ? <>
      <details className="rounded-xl border border-stone-200 p-4">
        <summary>当前版本 v{editor.current.version} · 查看正文与适用条件</summary>
        <p className="mt-2 text-sm text-stone-600">可勾选本次要修订的段落。</p>
        {editor.current.paragraphs.map((paragraph, index) => <label key={index} className="mt-3 flex gap-2 text-sm">
          <input type="checkbox" className="mt-1 self-start" checked={targets.includes(index)}
            onChange={(event) => setTargets(event.target.checked ? [...targets, index] : targets.filter((item) => item !== index))} />
          <span className="whitespace-pre-wrap">{index === editor.current.paragraphs.length - 1 ? "适用条件\n" : ""}{paragraph}</span>
        </label>)}
      </details>
      {references.length ? <section className="rounded-xl border border-stone-200 p-4">
        <h2 className="font-semibold">来源</h2>
        {references.map((ref) => <button key={ref} className="agui-button mt-2 mr-2" onClick={() => {
          setSource("");
          void apiFetch<string>("/api/knowledge/documents/source?ref=" + encodeURIComponent(ref))
            .then(setSource).catch((failure) => setSource(failure instanceof Error ? failure.message : "来源不可用"));
        }}>{ref}</button>)}
        {source ? <p className="mt-3 whitespace-pre-wrap text-sm">{source}</p> : null}
      </section> : null}
      <label className="block text-sm">修改要求
        <textarea className="mt-1 w-full rounded-xl border border-stone-200 p-3" rows={3} value={instruction}
          onChange={(event) => setInstruction(event.target.value)} placeholder="例如，只更新适用条件" />
      </label>
      <label className="block text-sm">补充来源引用（选填，每行一条）
        <textarea className="mt-1 w-full rounded-xl border border-stone-200 p-3" rows={2} value={additionalSources}
          onChange={(event) => setAdditionalSources(event.target.value)} />
      </label>
      <button type="button" className="agui-button" disabled={busy || !instruction.trim()}
        onClick={() => void update("propose")}>生成修改草稿</button>
      {editor.draft ? <section className="space-y-3">
        <h2 className="font-semibold">草稿基于 v{editor.draft.version}</h2>
        {stale ? <p role="status" className="text-amber-800">原条目已更新。可以核对当前版本，草稿仍可查看或复制。</p> : null}
        {editor.draft.changes.length === 0 ? <p>草稿没有提出修改。</p> : null}
        {editor.draft.changes.map((change) => <article className="space-y-2 rounded-xl border border-stone-200 p-4" key={change.index}>
          <label className="flex gap-2 text-sm"><input type="checkbox" checked={selected.includes(change.index)}
            onChange={(event) => setSelected(event.target.checked ? [...selected, change.index] : selected.filter((item) => item !== change.index))} />
            应用这一段修改</label>
          <p className="whitespace-pre-wrap rounded bg-stone-50 p-2 text-sm text-stone-600">{change.original}</p>
          <textarea aria-label="修改后的段落" rows={3} className="w-full rounded border border-stone-200 p-2 text-sm"
            value={edits[change.index] ?? change.replacement} onChange={(event) => setEdits({ ...edits, [change.index]: event.target.value })} />
        </article>)}
        <button type="button" className="agui-button agui-button-primary"
          disabled={busy || !!stale || !selected.length} onClick={() => void update("commit")}>应用所选修改</button>
      </section> : null}
      {editor.current.history.length ? <details className="rounded-xl border border-stone-200 p-4">
        <summary>版本历史</summary>
        <ol className="mt-2 space-y-1 text-sm">{editor.current.history.map((item) => <li key={item.version}>v{item.version} · {item.title}</li>)}</ol>
      </details> : null}
    </> : null}
  </main>;
}

"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  SystemPrompt,
  createSystemPrompt,
  deleteSystemPrompt,
  listSystemPrompts,
  setDefaultSystemPrompt,
  updateSystemPrompt,
} from "@/lib/system-prompts";

const emptyForm = { name: "", content: "" };

export default function SystemPromptsPage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [message, setMessage] = useState("");
  const [saving, setSaving] = useState(false);

  const selectedPrompt = useMemo(
    () => prompts.find((prompt) => prompt.id === selectedId) ?? null,
    [prompts, selectedId],
  );

  const refreshPrompts = useCallback(async () => {
    const list = await listSystemPrompts();
    setPrompts(list);
    const next = list.find((prompt) => prompt.isDefault) ?? list[0] ?? null;
    setSelectedId(next?.id ?? null);
    setForm(next ? { name: next.name, content: next.content } : emptyForm);
  }, []);

  useEffect(() => {
    getCurrentUser()
      .then(() => {
        setAuthenticated(true);
        return refreshPrompts();
      })
      .catch(() => {
        savePostLoginRedirect("/me/system-prompts");
        router.replace("/auth/login");
      });
  }, [refreshPrompts, router]);

  function selectPrompt(prompt: SystemPrompt) {
    setSelectedId(prompt.id);
    setForm({ name: prompt.name, content: prompt.content });
    setMessage("");
  }

  function startCreate() {
    setSelectedId(null);
    setForm(emptyForm);
    setMessage("");
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form.name.trim() || !form.content.trim() || saving) return;

    setSaving(true);
    setMessage("");
    try {
      const payload = { name: form.name, content: form.content };
      const saved = selectedId
        ? await updateSystemPrompt(selectedId, payload)
        : await createSystemPrompt(payload);
      const list = await listSystemPrompts();
      setPrompts(list);
      setSelectedId(saved.id);
      setForm({ name: saved.name, content: saved.content });
      setMessage("已保存");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function handleSetDefault() {
    if (!selectedId) return;
    try {
      await setDefaultSystemPrompt(selectedId);
      await refreshPrompts();
      setMessage("已设为默认");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "设置失败");
    }
  }

  async function handleDelete() {
    if (!selectedId) return;
    try {
      await deleteSystemPrompt(selectedId);
      await refreshPrompts();
      setMessage("已删除");
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "删除失败");
    }
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
          <h1 className="mt-3 text-2xl font-semibold">SystemPrompt 管理</h1>
          <p className="mt-2 text-sm text-stone-500">创建你的私有系统提示词，并选择一个作为默认助手。</p>
        </header>

        <div className="flex gap-2 overflow-x-auto pb-1">
          {prompts.map((prompt) => (
            <button
              key={prompt.id}
              className={`shrink-0 rounded-full border px-4 py-2 text-sm shadow-sm ${
                prompt.id === selectedId
                  ? "border-stone-900 bg-stone-900 text-white"
                  : "border-stone-200 bg-white/90 text-stone-600"
              }`}
              type="button"
              onClick={() => selectPrompt(prompt)}
            >
              {prompt.name}
              {prompt.isDefault ? " · 默认" : ""}
            </button>
          ))}
          <button
            className="shrink-0 rounded-full border border-stone-200 bg-white/90 px-4 py-2 text-sm text-stone-600 shadow-sm"
            type="button"
            onClick={startCreate}
          >
            新建
          </button>
        </div>

        <form
          className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur"
          onSubmit={handleSubmit}
        >
          {message ? <p className="mb-3 rounded-2xl bg-stone-100 px-4 py-3 text-sm text-stone-700">{message}</p> : null}
          <label className="block text-sm font-medium text-stone-700" htmlFor="prompt-name">
            名称
          </label>
          <input
            id="prompt-name"
            className="mt-2 w-full rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
            value={form.name}
            onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
            maxLength={64}
          />

          <label className="mt-4 block text-sm font-medium text-stone-700" htmlFor="prompt-content">
            系统提示词
          </label>
          <textarea
            id="prompt-content"
            className="mt-2 min-h-56 w-full resize-none rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 text-sm leading-6 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
            value={form.content}
            onChange={(event) => setForm((current) => ({ ...current, content: event.target.value }))}
            maxLength={8000}
          />

          <div className="mt-4 grid grid-cols-1 gap-2">
            <button
              className="rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
              type="submit"
              disabled={saving || !form.name.trim() || !form.content.trim()}
            >
              {saving ? "保存中" : selectedId ? "保存修改" : "创建提示词"}
            </button>
            {selectedPrompt ? (
              <button
                className="rounded-2xl border border-stone-200 px-4 py-3 text-sm font-semibold text-stone-700"
                type="button"
                onClick={handleSetDefault}
              >
                设为默认
              </button>
            ) : null}
            {selectedPrompt ? (
              <button
                className="rounded-2xl border border-red-200 px-4 py-3 text-sm font-semibold text-red-600"
                type="button"
                onClick={handleDelete}
              >
                删除
              </button>
            ) : null}
          </div>
        </form>
      </section>
    </main>
  );
}

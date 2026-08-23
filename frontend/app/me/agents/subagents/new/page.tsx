"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  SUBAGENT_TEMPLATE,
  createSubagentDefinition,
  isValidSubagentAgentId,
} from "@/lib/subagent-catalog";

export default function NewSubagentPage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [agentId, setAgentId] = useState("");
  const [markdown, setMarkdown] = useState(SUBAGENT_TEMPLATE);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    getCurrentUser()
      .then(() => setAuthenticated(true))
      .catch(() => {
        savePostLoginRedirect("/me/agents/subagents/new");
        router.replace("/auth/login");
      });
  }, [router]);

  const agentIdTouched = agentId.trim().length > 0;
  const agentIdValid = isValidSubagentAgentId(agentId.trim());

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedAgentId = agentId.trim();
    if (!trimmedAgentId || !markdown.trim() || submitting) return;

    setSubmitting(true);
    setError("");
    try {
      await createSubagentDefinition({ agentId: trimmedAgentId, markdown });
      router.replace(`/me/agents/subagents/${encodeURIComponent(trimmedAgentId)}`);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "创建失败");
    } finally {
      setSubmitting(false);
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
          <h1 className="mt-3 text-2xl font-semibold">新建 Subagent</h1>
          <p className="mt-2 text-sm text-stone-500">
            Agent ID 创建后不可修改；正文使用 Markdown 定义，front matter 字段与内置 Subagent 同构。
          </p>
        </header>

        <form
          className="space-y-4 rounded-lg border border-stone-200 bg-white/90 p-4 shadow-sm"
          onSubmit={handleSubmit}
        >
          {error ? <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p> : null}

          <div>
            <label className="block text-sm font-medium text-stone-700" htmlFor="subagent-agent-id">
              Agent ID
            </label>
            <input
              id="subagent-agent-id"
              className="mt-2 h-11 w-full rounded-lg border border-stone-200 bg-stone-50/60 px-3 font-mono text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
              value={agentId}
              onChange={(event) => setAgentId(event.target.value)}
              placeholder="my-reviewer"
              maxLength={63}
              spellCheck={false}
            />
            {agentIdTouched && !agentIdValid ? (
              <p className="mt-2 text-xs text-red-500">
                仅限小写字母、数字和中划线（kebab-case），长度 1–63，不能用内置 ID。
              </p>
            ) : null}
          </div>

          <div>
            <label className="block text-sm font-medium text-stone-700" htmlFor="subagent-markdown">
              定义 Markdown
            </label>
            <textarea
              id="subagent-markdown"
              className="mt-2 min-h-80 w-full rounded-lg border border-stone-200 bg-stone-50/60 px-3 py-3 font-mono text-xs leading-5 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
              value={markdown}
              onChange={(event) => setMarkdown(event.target.value)}
              spellCheck={false}
            />
          </div>

          <button
            type="submit"
            className="w-full rounded-lg bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
            disabled={submitting || !agentIdValid || !markdown.trim()}
          >
            {submitting ? "创建中" : "创建草稿"}
          </button>
        </form>
      </section>
    </main>
  );
}

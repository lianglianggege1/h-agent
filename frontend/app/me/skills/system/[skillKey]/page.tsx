"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { MarkdownContent } from "@/components/markdown-content";
import { ApiError } from "@/lib/http";
import { savePostLoginRedirect } from "@/lib/session";
import { getSystemSkill, type SystemSkillDetail } from "@/lib/skills";

export default function SystemSkillPage() {
  const { skillKey } = useParams<{ skillKey: string }>();
  return <SystemSkillReader key={skillKey} skillKey={skillKey} />;
}

function SystemSkillReader({ skillKey }: { skillKey: string }) {
  const router = useRouter();
  const [detail, setDetail] = useState<SystemSkillDetail | null>(null);
  const [error, setError] = useState("");
  const [selectedPath, setSelectedPath] = useState("SKILL.md");
  const [showSource, setShowSource] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    getSystemSkill(skillKey, controller.signal).then((data) => {
      if (!controller.signal.aborted) setDetail(data);
    }).catch((failure) => {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.code === 40100) {
        savePostLoginRedirect(`/me/skills/system/${encodeURIComponent(skillKey)}`);
        router.replace("/auth/login");
        return;
      }
      setError(failure instanceof ApiError && failure.code === 40404
        ? "这个系统内置 Skill 不存在。"
        : "暂时无法读取系统内置 Skill。");
    });
    return () => controller.abort();
  }, [router, skillKey]);

  const skill = detail?.summary;
  const file = detail?.files.find((item) => item.path === selectedPath) ?? detail?.files[0];
  const isMarkdown = file?.path.endsWith(".md") ?? false;

  return (
    <main className="workspace-page resource-page text-stone-900">
      <section className="workspace-container space-y-4">
        <header className="rounded-2xl border border-stone-200/80 bg-white p-5">
          <Link className="text-sm text-stone-600 hover:underline" href="/me/skills">返回技能</Link>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <h1 className="break-words text-2xl font-semibold">{skill?.displayName ?? "系统内置 Skill"}</h1>
            {skill ? <span className="rounded-full bg-stone-100 px-2.5 py-1 text-xs font-medium text-stone-600">系统内置 · 只读</span> : null}
          </div>
          {skill ? <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-stone-500">
            <code className="break-all text-xs">{skill.skillKey}</code>
            {skill.revision ? <span>版本 {skill.revision}</span> : null}
            {!skill.enabled ? <span>已停用</span> : null}
          </div> : null}
        </header>

        {error ? <p role="alert" className="rounded-2xl bg-red-50 p-4 text-sm text-red-600">{error}</p> : null}
        {!detail && !error ? <p role="status" className="p-4 text-sm text-stone-500">正在读取 Skill…</p> : null}
        {detail && file ? (
          <article className="min-w-0 rounded-2xl border border-stone-200 bg-white">
            <div className="flex flex-wrap items-center justify-between gap-3 border-b border-stone-200 p-3 sm:px-5">
              <div role="group" aria-label="Skill 文件" className="flex flex-wrap gap-2">
                {detail.files.map((item) => (
                  <button
                    key={item.path}
                    type="button"
                    aria-pressed={file.path === item.path}
                    className={`rounded-lg px-3 py-1.5 font-mono text-xs ${file.path === item.path
                      ? "bg-stone-900 font-semibold text-white" : "text-stone-600 hover:bg-stone-100"}`}
                    onClick={() => { setSelectedPath(item.path); setShowSource(false); }}
                  >
                    {item.path}
                  </button>
                ))}
              </div>
              {isMarkdown ? <button
                type="button"
                className="rounded-lg border border-stone-200 px-3 py-1.5 text-xs text-stone-600 hover:bg-stone-100"
                onClick={() => setShowSource((current) => !current)}
              >
                {showSource ? "查看预览" : "查看源文"}
              </button> : null}
            </div>
            <div className="max-w-4xl p-4 text-sm leading-7 sm:p-6">
              {isMarkdown && !showSource
                ? <MarkdownContent content={file.content.replace(/^---[ \t]*\r?\n[\s\S]*?\r?\n---[ \t]*(?:\r?\n|$)/, "").trim()} />
                : <pre aria-label={file.path} className="overflow-x-auto rounded-xl bg-stone-100 p-4 font-mono text-xs leading-6 text-stone-800">{file.content}</pre>}
            </div>
          </article>
        ) : null}
      </section>
    </main>
  );
}

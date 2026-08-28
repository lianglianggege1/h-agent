"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { SkillSummary, createSkill, listSkills } from "@/lib/skills";

const DEFAULT_SKILL_MD = `---
name: my-skill
description: 这个 Skill 做什么、什么时候用
---

# 步骤

1. ...

# 参考

- ...
`;

function formatDate(value: string | null) {
  if (!value) return "未发布";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function validationStatusClassName(status: string | null) {
  if (!status) return "border-stone-200 bg-white text-stone-500";
  if (status === "VALID") return "border-emerald-200 bg-emerald-50 text-emerald-700";
  if (status === "INVALID") return "border-red-200 bg-red-50 text-red-600";
  return "border-amber-200 bg-amber-50 text-amber-700";
}

function validationStatusLabel(status: string | null) {
  if (!status) return "未校验";
  if (status === "VALID") return "草稿校验通过";
  if (status === "INVALID") return "草稿校验未通过";
  return status;
}

export default function SkillsPage() {
  const router = useRouter();
  const [authenticated, setAuthenticated] = useState(false);
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [skillKey, setSkillKey] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [description, setDescription] = useState("");
  const [skillMd, setSkillMd] = useState(DEFAULT_SKILL_MD);
  const [creating, setCreating] = useState(false);
  const mountedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        const list = await listSkills();
        if (!mountedRef.current) return;
        setSkills(list);
      })
      .catch(() => {
        if (!mountedRef.current) return;
        savePostLoginRedirect("/me/skills");
        router.replace("/auth/login");
      })
      .finally(() => {
        if (mountedRef.current) setLoading(false);
      });

    return () => {
      mountedRef.current = false;
    };
  }, [router]);

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (creating) return;

    setCreating(true);
    setError("");
    setMessage("");
    try {
      const created = await createSkill({
        skillKey: skillKey.trim(),
        displayName: displayName.trim(),
        description: description.trim(),
        skillMd,
      });
      if (!mountedRef.current) return;
      setMessage(`已创建「${created.displayName}」，正在打开草稿...`);
      router.push(`/me/skills/${created.id}`);
    } catch (createError) {
      if (!mountedRef.current) return;
      setError(createError instanceof Error ? createError.message : "创建失败");
    } finally {
      if (mountedRef.current) setCreating(false);
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
          <p className="mt-4 text-xs uppercase tracking-[0.28em] text-amber-700">Skills</p>
          <h1 className="mt-2 text-2xl font-semibold">我的 Skill</h1>
          <p className="mt-2 text-sm leading-6 text-stone-500">
            每个改动先进入草稿（Proposal），校验通过后发布为不可变版本，再手动设为生效。
          </p>
        </header>

        {message ? (
          <p className="rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{message}</p>
        ) : null}
        {error ? <p className="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <button
            className="w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800"
            type="button"
            onClick={() => {
              setMessage("");
              setError("");
              setCreateOpen((current) => !current);
            }}
          >
            {createOpen ? "收起创建表单" : "新建 Skill"}
          </button>

          {createOpen ? (
            <form className="mt-4 space-y-3" onSubmit={handleCreate}>
              <div>
                <label className="block text-sm font-semibold text-stone-700" htmlFor="skill-key">
                  标识（skillKey）
                </label>
                <p className="mt-1 text-xs leading-5 text-stone-500">
                  小写字母开头，仅含小写字母、数字和中划线；发布后不可修改。
                </p>
                <input
                  id="skill-key"
                  className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={skillKey}
                  onChange={(event) => setSkillKey(event.target.value)}
                  maxLength={63}
                  placeholder="my-skill"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-semibold text-stone-700" htmlFor="skill-display-name">
                  显示名称
                </label>
                <input
                  id="skill-display-name"
                  className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  maxLength={60}
                  placeholder="视频提示词优化"
                  required
                />
              </div>
              <div>
                <label className="block text-sm font-semibold text-stone-700" htmlFor="skill-description">
                  说明
                </label>
                <input
                  id="skill-description"
                  className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={200}
                  placeholder="一句话说明用途"
                />
              </div>
              <div>
                <label className="block text-sm font-semibold text-stone-700" htmlFor="skill-md">
                  SKILL.md 初始内容
                </label>
                <textarea
                  id="skill-md"
                  className="mt-2 min-h-56 w-full resize-y rounded-2xl border border-stone-200 bg-white px-4 py-3 font-mono text-xs leading-5 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={skillMd}
                  onChange={(event) => setSkillMd(event.target.value)}
                  required
                />
              </div>
              <button
                className="w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
                type="submit"
                disabled={creating || !skillKey.trim() || !displayName.trim() || !skillMd.trim()}
              >
                {creating ? "创建中" : "创建并打开草稿"}
              </button>
            </form>
          ) : null}
        </section>

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-xs uppercase tracking-[0.2em] text-amber-700">User Skills</p>
              <h2 className="mt-1 text-lg font-semibold">我的 Skill（{skills.length}）</h2>
            </div>
            {loading ? <p className="shrink-0 text-sm text-stone-500">加载中...</p> : null}
          </div>

          {!loading && skills.length === 0 ? (
            <p className="mt-4 rounded-2xl bg-stone-50 px-4 py-5 text-sm leading-6 text-stone-500">
              还没有 Skill。点击上方「新建 Skill」创建第一个。
            </p>
          ) : null}

          <div className="mt-4 space-y-3">
            {skills.map((skill) => (
              <article key={skill.id} className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h3 className="break-words text-sm font-semibold text-stone-800">
                      <Link className="hover:text-amber-700" href={`/me/skills/${skill.id}`}>
                        {skill.displayName}
                      </Link>
                    </h3>
                    <p className="mt-1 break-words font-mono text-xs text-stone-500">{skill.skillKey}</p>
                  </div>
                  <span
                    className={`shrink-0 rounded-full border px-3 py-1 text-xs font-medium ${
                      skill.archived
                        ? "border-stone-300 bg-stone-100 text-stone-500"
                        : skill.enabled
                          ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                          : "border-amber-200 bg-amber-50 text-amber-700"
                    }`}
                  >
                    {skill.archived ? "已归档" : skill.enabled ? "已启用" : "已停用"}
                  </span>
                </div>

                {skill.description ? (
                  <p className="mt-2 break-words text-xs leading-5 text-stone-600">{skill.description}</p>
                ) : null}

                <dl className="mt-3 grid grid-cols-2 gap-3 text-xs text-stone-500">
                  <div>
                    <dt>生效版本</dt>
                    <dd className="mt-1 text-sm text-stone-700">
                      {skill.activeVersion ? `v${skill.activeVersion}` : "未生效"}
                    </dd>
                  </div>
                  <div>
                    <dt>最近发布</dt>
                    <dd className="mt-1 text-sm text-stone-700">{formatDate(skill.lastPublishedAt)}</dd>
                  </div>
                </dl>

                <div className="mt-3 flex flex-wrap gap-2">
                  {skill.hasOpenProposal ? (
                    <span
                      className={`rounded-full border px-3 py-1 text-xs font-medium ${validationStatusClassName(
                        skill.openProposalValidationStatus,
                      )}`}
                    >
                      有草稿 · {validationStatusLabel(skill.openProposalValidationStatus)}
                    </span>
                  ) : null}
                  <Link
                    className="rounded-2xl border border-stone-300 bg-white px-3 py-2 text-xs font-semibold text-stone-700"
                    href={`/me/skills/${skill.id}`}
                  >
                    打开工作区
                  </Link>
                </div>
              </article>
            ))}
          </div>
        </section>
      </section>
    </main>
  );
}

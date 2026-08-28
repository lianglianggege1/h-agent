"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  ReleaseCompare,
  ReleaseDetail,
  ReleaseSummary,
  SkillSummary,
  activateRelease,
  compareReleases,
  decodeFileContent,
  getRelease,
  getSkill,
  listReleases,
  revokeRelease,
} from "@/lib/skills";

function formatDate(value: string | null) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function formatSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function isTextPath(path: string) {
  const extension = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  return ["md", "txt", "json", "yaml", "yml"].includes(extension);
}

const CHANGE_LABELS: Record<string, string> = {
  added: "新增",
  modified: "修改",
  removed: "删除",
};

const CHANGE_CLASSES: Record<string, string> = {
  added: "border-emerald-200 bg-emerald-50 text-emerald-700",
  modified: "border-amber-200 bg-amber-50 text-amber-700",
  removed: "border-red-200 bg-red-50 text-red-600",
};

export default function ReleaseDetailPage() {
  const router = useRouter();
  const params = useParams<{ skillId: string; releaseId: string }>();
  const skillId = Number(params.skillId);
  const releaseId = Number(params.releaseId);
  const mountedRef = useRef(false);

  const [authenticated, setAuthenticated] = useState(false);
  const [skill, setSkill] = useState<SkillSummary | null>(null);
  const [release, setRelease] = useState<ReleaseDetail | null>(null);
  const [releases, setReleases] = useState<ReleaseSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState("");
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [compareFromId, setCompareFromId] = useState<number | null>(null);
  const [compare, setCompare] = useState<ReleaseCompare | null>(null);
  const [revokeReason, setRevokeReason] = useState("");

  const isActive = release?.isActive ?? false;
  const revoked = release?.summary.revoked ?? false;

  const otherReleases = useMemo(
    () => releases.filter((item) => item.id !== releaseId),
    [releases, releaseId],
  );

  const selectedFile = useMemo(() => {
    if (!release || !selectedPath) return null;
    return release.files.find((file) => file.path === selectedPath) ?? null;
  }, [release, selectedPath]);

  const selectedContent = useMemo(() => {
    if (!selectedFile) return "";
    return isTextPath(selectedFile.path) ? decodeFileContent(selectedFile.contentBase64) : "（二进制文件，不支持在线预览）";
  }, [selectedFile]);

  useEffect(() => {
    mountedRef.current = true;
    if (!Number.isFinite(skillId) || !Number.isFinite(releaseId)) return;
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        const [nextSkill, nextReleases, nextRelease] = await Promise.all([
          getSkill(skillId),
          listReleases(skillId),
          getRelease(skillId, releaseId),
        ]);
        if (!mountedRef.current) return;
        setSkill(nextSkill);
        setReleases(nextReleases);
        setRelease(nextRelease);
        const paths = nextRelease.files.map((file) => file.path);
        setSelectedPath(paths.includes("SKILL.md") ? "SKILL.md" : (paths[0] ?? null));
        const compareCandidate = nextReleases.find(
          (item) => item.id !== releaseId && item.id === nextSkill.activeReleaseId,
        );
        if (compareCandidate) setCompareFromId(compareCandidate.id);
      })
      .catch(() => {
        if (!mountedRef.current) return;
        savePostLoginRedirect(`/me/skills/${skillId}/releases/${releaseId}`);
        router.replace("/auth/login");
      })
      .finally(() => {
        if (mountedRef.current) setLoading(false);
      });

    return () => {
      mountedRef.current = false;
    };
  }, [skillId, releaseId, router]);

  async function runCompare(fromId: number) {
    setCompareFromId(fromId);
    setCompare(null);
    setError("");
    if (!fromId) return;
    try {
      const result = await compareReleases(skillId, fromId, releaseId);
      if (mountedRef.current) setCompare(result);
    } catch (compareError) {
      if (mountedRef.current) {
        setError(compareError instanceof Error ? compareError.message : "比较失败");
      }
    }
  }

  async function handleActivate() {
    if (busy !== "" || !skill) return;
    if (!window.confirm("确定把该版本设为当前生效版本吗？聊天运行时将从下一次请求开始使用它。")) return;
    setBusy("activate");
    setError("");
    setMessage("");
    try {
      const next = await activateRelease(skillId, releaseId, skill.revision);
      if (!mountedRef.current) return;
      setSkill(next);
      setRelease((current) => (current ? { ...current, isActive: true } : current));
      setMessage("已设为生效版本");
    } catch (activateError) {
      if (mountedRef.current) {
        setError(activateError instanceof Error ? activateError.message : "设置生效失败");
        try {
          setSkill(await getSkill(skillId));
        } catch {
          // 刷新失败时保留原错误
        }
      }
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleRevoke() {
    if (busy !== "" || !release) return;
    if (!window.confirm("确定撤销该版本吗？撤销后不可恢复，且不能再次设为生效。")) return;
    setBusy("revoke");
    setError("");
    setMessage("");
    try {
      await revokeRelease(skillId, releaseId, revokeReason.trim() || null);
      if (!mountedRef.current) return;
      const next = await getRelease(skillId, releaseId);
      if (mountedRef.current) {
        setRelease(next);
        setSkill(await getSkill(skillId));
        setMessage("版本已撤销");
      }
    } catch (revokeError) {
      if (mountedRef.current) {
        setError(revokeError instanceof Error ? revokeError.message : "撤销失败");
        try {
          setSkill(await getSkill(skillId));
        } catch {
          // 刷新失败时保留原错误
        }
      }
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  if (loading || !release || !skill) {
    return (
      <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
        <section className="mx-auto w-full max-w-md space-y-4">
          <p className="rounded-2xl bg-white/90 px-4 py-5 text-sm text-stone-500">
            {error ? error : "加载中..."}
          </p>
        </section>
      </main>
    );
  }

  const summary = release.summary;

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href={`/me/skills/${skillId}`}>
            返回 {skill.displayName} 工作区
          </Link>
          <p className="mt-4 text-xs uppercase tracking-[0.28em] text-amber-700">Release</p>
          <h1 className="mt-2 text-2xl font-semibold">
            v{summary.versionNumber}
            {isActive ? " · 当前生效" : ""}
          </h1>
          <div className="mt-3 flex flex-wrap gap-2">
            <span
              className={`rounded-full border px-3 py-1 text-xs font-medium ${
                revoked
                  ? "border-red-200 bg-red-50 text-red-600"
                  : "border-stone-200 bg-white text-stone-600"
              }`}
            >
              {revoked ? "已撤销" : "可用"}
            </span>
            <span className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs text-stone-600">
              {formatSize(summary.size)}
            </span>
          </div>
          <dl className="mt-4 space-y-2 text-xs text-stone-500">
            <div>
              <dt>发布时间</dt>
              <dd className="mt-0.5 text-sm text-stone-700">{formatDate(summary.createdAt)}</dd>
            </div>
            <div>
              <dt>Commit</dt>
              <dd className="mt-0.5 break-all font-mono text-sm text-stone-700">{summary.commitSha}</dd>
            </div>
            <div>
              <dt>Artifact Digest</dt>
              <dd className="mt-0.5 break-all font-mono text-xs text-stone-700">{summary.digest}</dd>
            </div>
            <div>
              <dt>版本说明</dt>
              <dd className="mt-0.5 text-sm leading-6 text-stone-700">{summary.releaseNote || "（无）"}</dd>
            </div>
            {revoked && summary.revokeReason ? (
              <div>
                <dt>撤销原因</dt>
                <dd className="mt-0.5 text-sm leading-6 text-red-600">{summary.revokeReason}</dd>
              </div>
            ) : null}
          </dl>
        </header>

        {message ? (
          <p className="rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{message}</p>
        ) : null}
        {error ? <p className="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Integrity</p>
          <h2 className="mt-1 text-lg font-semibold">完整性</h2>
          <dl className="mt-3 grid grid-cols-1 gap-2 text-xs text-stone-500">
            <div>
              <dt>Builder</dt>
              <dd className="mt-0.5 font-mono text-stone-700">{release.builderVersion}</dd>
            </div>
            <div>
              <dt>校验策略</dt>
              <dd className="mt-0.5 font-mono text-stone-700">{release.validationPolicyVersion}</dd>
            </div>
            <div>
              <dt>安全策略</dt>
              <dd className="mt-0.5 font-mono text-stone-700">{release.securityPolicyVersion}</dd>
            </div>
          </dl>
          {release.validationWarnings.length > 0 ? (
            <ul className="mt-3 list-inside list-disc space-y-1 rounded-2xl bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-700">
              {release.validationWarnings.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ul>
          ) : null}
        </section>

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Files</p>
          <h2 className="mt-1 text-lg font-semibold">文件（{release.files.length}）</h2>
          <div className="mt-4 space-y-2">
            {release.files.map((file) => (
              <button
                key={file.path}
                className={`w-full truncate rounded-2xl border px-4 py-2.5 text-left font-mono text-xs ${
                  selectedPath === file.path
                    ? "border-stone-900 bg-stone-900 text-white"
                    : "border-stone-200 bg-stone-50/70 text-stone-700"
                }`}
                type="button"
                onClick={() => setSelectedPath(file.path)}
              >
                {file.path} · {formatSize(file.size)}
              </button>
            ))}
          </div>

          {selectedFile ? (
            <div className="mt-4">
              <p className="mb-2 font-mono text-xs text-stone-500">{selectedFile.path}</p>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 font-mono text-xs leading-5 text-stone-800">
                {selectedContent}
              </pre>
            </div>
          ) : null}

          <details className="mt-4">
            <summary className="cursor-pointer text-sm font-semibold text-stone-700">Manifest</summary>
            <ul className="mt-2 space-y-1">
              {release.manifest.map((entry) => (
                <li key={entry.path} className="break-all font-mono text-xs text-stone-500">
                  {entry.path} · {formatSize(entry.size)} · {entry.sha256.slice(0, 16)}…
                </li>
              ))}
            </ul>
          </details>
        </section>

        {otherReleases.length > 0 ? (
          <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
            <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Diff</p>
            <h2 className="mt-1 text-lg font-semibold">版本比较</h2>
            <p className="mt-2 text-xs leading-5 text-stone-500">
              选择一个旧版本，查看它到 v{summary.versionNumber} 的文件级差异。
            </p>
            <div className="mt-3 flex gap-2 overflow-x-auto pb-1">
              {otherReleases.map((item) => (
                <button
                  key={item.id}
                  className={`shrink-0 rounded-full border px-4 py-2 text-sm transition ${
                    compareFromId === item.id
                      ? "border-stone-900 bg-stone-900 text-white"
                      : "border-stone-200 bg-white/90 text-stone-600"
                  }`}
                  type="button"
                  onClick={() => void runCompare(item.id)}
                >
                  v{item.versionNumber}
                  {skill.activeReleaseId === item.id ? " · 生效" : ""}
                </button>
              ))}
            </div>

            {compare ? (
              <div className="mt-4 space-y-2">
                <p className="text-xs text-stone-500">
                  v{compare.fromVersion} → v{compare.toVersion}：新增 {compare.filesAdded}、修改{" "}
                  {compare.filesModified}、删除 {compare.filesRemoved}
                </p>
                {compare.changes.length === 0 ? (
                  <p className="rounded-2xl bg-stone-50 px-4 py-3 text-sm text-stone-500">
                    两个版本内容完全一致。
                  </p>
                ) : (
                  <div className="space-y-2">
                    {compare.changes.map((change) => (
                      <div
                        key={change.path}
                        className="flex items-center gap-2 rounded-2xl border border-stone-200 bg-stone-50/70 px-4 py-2.5"
                      >
                        <span className="min-w-0 flex-1 truncate font-mono text-xs text-stone-700">
                          {change.path}
                        </span>
                        <span
                          className={`shrink-0 rounded-full border px-3 py-1 text-xs font-medium ${
                            CHANGE_CLASSES[change.change] ?? "border-stone-200 bg-white text-stone-600"
                          }`}
                        >
                          {CHANGE_LABELS[change.change] ?? change.change}
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : null}
          </section>
        ) : null}

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Actions</p>
          <h2 className="mt-1 text-lg font-semibold">操作</h2>
          <div className="mt-4 space-y-3">
            <button
              className="w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
              type="button"
              disabled={busy !== "" || revoked || isActive}
              onClick={() => void handleActivate()}
            >
              {isActive ? "已是生效版本" : revoked ? "已撤销，不能设为生效" : "设为生效版本"}
            </button>

            {!revoked ? (
              <div className="space-y-2">
                <input
                  className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={revokeReason}
                  onChange={(event) => setRevokeReason(event.target.value)}
                  maxLength={200}
                  placeholder="撤销原因（可选）"
                />
                <button
                  className="w-full rounded-2xl border border-red-200 px-4 py-3 text-sm font-semibold text-red-600 disabled:text-red-300"
                  type="button"
                  disabled={busy !== ""}
                  onClick={() => void handleRevoke()}
                >
                  {busy === "revoke" ? "撤销中" : "撤销该版本"}
                </button>
              </div>
            ) : null}

            <p className="text-xs leading-5 text-stone-500">
              版本内容不可变：撤销只是运行时不再加载它，历史记录与制品都会保留。
            </p>
          </div>
        </section>
      </section>
    </main>
  );
}

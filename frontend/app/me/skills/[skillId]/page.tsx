"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  Proposal,
  ReleaseSummary,
  SkillSummary,
  ValidationOutcome,
  archiveSkill,
  createProposal,
  decodeFileContent,
  deleteSkill,
  discardProposal,
  encodeFileContent,
  getProposal,
  getSkill,
  listReleases,
  publishRelease,
  restoreSkill,
  saveProposal,
  setSkillEnabled,
  skillStatusLabel,
  skillStatusClassName,
  validateProposal,
} from "@/lib/skills";

const TEXT_EXTENSIONS = ["md", "txt", "json", "yaml", "yml"];

function formatDate(value: string | null) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", { hour12: false });
}

function isTextPath(path: string) {
  const dot = path.lastIndexOf(".");
  const extension = dot < 0 ? "" : path.slice(dot + 1).toLowerCase();
  return TEXT_EXTENSIONS.includes(extension);
}

function shortCommit(sha: string) {
  return sha.length > 10 ? `${sha.slice(0, 10)}…` : sha;
}

export default function SkillWorkspacePage() {
  const router = useRouter();
  const params = useParams<{ skillId: string }>();
  const skillId = Number(params.skillId);
  const mountedRef = useRef(false);

  const [authenticated, setAuthenticated] = useState(false);
  const [skill, setSkill] = useState<SkillSummary | null>(null);
  const [releases, setReleases] = useState<ReleaseSummary[]>([]);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [proposalMissing, setProposalMissing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState("");

  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [newFilePath, setNewFilePath] = useState("");
  const [validation, setValidation] = useState<ValidationOutcome | null>(null);
  const [releaseNote, setReleaseNote] = useState("");

  const validatable = proposal !== null && Object.keys(drafts).length === 0;
  const validatedCurrent =
    validation !== null && proposal !== null && validation.headCommitSha === proposal.headCommitSha;

  async function reloadProposal(nextSkillId: number) {
    try {
      const nextProposal = await getProposal(nextSkillId);
      if (!mountedRef.current) return;
      setProposal(nextProposal);
      setProposalMissing(false);
      const paths = nextProposal.files.map((file) => file.path);
      setSelectedPath((current) => (current && paths.includes(current) ? current : paths[0] ?? null));
    } catch {
      if (!mountedRef.current) return;
      setProposal(null);
      setProposalMissing(true);
      setSelectedPath(null);
    } finally {
      if (mountedRef.current) {
        setDrafts({});
        setValidation(null);
      }
    }
  }

  async function reloadAll(nextSkillId: number) {
    const [nextSkill, nextReleases] = await Promise.all([
      getSkill(nextSkillId),
      listReleases(nextSkillId),
    ]);
    if (!mountedRef.current) return;
    setSkill(nextSkill);
    setReleases(nextReleases);
    await reloadProposal(nextSkillId);
  }

  useEffect(() => {
    mountedRef.current = true;
    if (!Number.isFinite(skillId)) return;
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        await reloadAll(skillId);
      })
      .catch(() => {
        if (!mountedRef.current) return;
        savePostLoginRedirect(`/me/skills/${skillId}`);
        router.replace("/auth/login");
      })
      .finally(() => {
        if (mountedRef.current) setLoading(false);
      });

    return () => {
      mountedRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skillId, router]);

  const proposalPaths = useMemo(() => {
    const paths = new Set(proposal?.files.map((file) => file.path) ?? []);
    for (const draftPath of Object.keys(drafts)) {
      if (drafts[draftPath] !== null) paths.add(draftPath);
    }
    return [...paths].sort();
  }, [proposal, drafts]);

  const selectedContent = useMemo(() => {
    if (!selectedPath) return "";
    if (selectedPath in drafts) return drafts[selectedPath] ?? "";
    const file = proposal?.files.find((item) => item.path === selectedPath);
    return file ? decodeFileContent(file.contentBase64) : "";
  }, [selectedPath, drafts, proposal]);

  const dirty = Object.keys(drafts).length > 0;

  function guard(action: string) {
    if (busy) return false;
    setBusy(action);
    setError("");
    setMessage("");
    return true;
  }

  async function handleCreateProposal() {
    if (!guard("create-proposal")) return;
    try {
      await createProposal(skillId, skill?.activeReleaseId ?? null);
      if (!mountedRef.current) return;
      setMessage("草稿已创建，可以开始编辑");
      await reloadProposal(skillId);
    } catch (createError) {
      if (mountedRef.current) setError(createError instanceof Error ? createError.message : "创建草稿失败");
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleSave() {
    if (!guard("save") || !proposal) return;
    try {
      const changes = Object.entries(drafts).map(([path, content]) => ({
        path,
        contentBase64: content === null ? null : encodeFileContent(content),
      }));
      const next = await saveProposal(skillId, proposal.headCommitSha, changes);
      if (!mountedRef.current) return;
      setProposal(next);
      setDrafts({});
      setValidation(null);
      const paths = next.files.map((file) => file.path);
      setSelectedPath((current) => (current && paths.includes(current) ? current : paths[0] ?? null));
      setMessage("草稿已保存");
    } catch (saveError) {
      if (mountedRef.current) {
        setError(saveError instanceof Error ? saveError.message : "保存草稿失败");
        try {
          await reloadProposal(skillId);
        } catch {
          // 保存失败后重拉草稿失败时保留原错误
        }
      }
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleValidate() {
    if (!guard("validate") || !proposal) return;
    try {
      const outcome = await validateProposal(skillId, proposal.headCommitSha);
      if (!mountedRef.current) return;
      setValidation(outcome);
      setMessage(outcome.valid ? "校验通过，可以发布" : "校验未通过，请按错误提示修改");
    } catch (validateError) {
      if (mountedRef.current) setError(validateError instanceof Error ? validateError.message : "校验失败");
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handlePublish(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!guard("publish") || !proposal || !validatedCurrent || !validation?.valid) return;
    try {
      const release = await publishRelease(
        skillId,
        proposal.headCommitSha,
        validation.headCommitSha,
        releaseNote.trim(),
      );
      if (!mountedRef.current) return;
      router.push(`/me/skills/${skillId}/releases/${release.id}`);
    } catch (publishError) {
      if (mountedRef.current) {
        setError(publishError instanceof Error ? publishError.message : "发布失败");
        try {
          await reloadProposal(skillId);
        } catch {
          // 发布失败后重拉草稿失败时保留原错误
        }
      }
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleDiscard() {
    if (!guard("discard") || !proposal) return;
    if (!window.confirm("确定放弃当前草稿吗？未发布的修改将丢失。")) {
      setBusy("");
      return;
    }
    try {
      await discardProposal(skillId, proposal.headCommitSha);
      if (!mountedRef.current) return;
      setMessage("草稿已放弃");
      await reloadProposal(skillId);
    } catch (discardError) {
      if (mountedRef.current) setError(discardError instanceof Error ? discardError.message : "放弃草稿失败");
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleDeleteFile(path: string) {
    if (!guard(`delete-file:${path}`)) return;
    if (!window.confirm(`确定从草稿中删除「${path}」吗？`)) {
      setBusy("");
      return;
    }
    try {
      if (path in drafts && !proposal?.files.some((file) => file.path === path)) {
        const nextDrafts = { ...drafts };
        delete nextDrafts[path];
        if (mountedRef.current) {
          setDrafts(nextDrafts);
          if (selectedPath === path) setSelectedPath(null);
        }
      } else if (proposal) {
        await saveProposal(skillId, proposal.headCommitSha, [{ path, contentBase64: null }]);
        await reloadProposal(skillId);
        if (mountedRef.current) setMessage(`已删除 ${path}`);
      }
    } catch (deleteError) {
      if (mountedRef.current) setError(deleteError instanceof Error ? deleteError.message : "删除文件失败");
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  async function handleAssetUpload(file: File) {
    if (!guard("upload-asset") || !proposal) return;
    try {
      const base64 = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
          const result = String(reader.result ?? "");
          resolve(result.slice(result.indexOf(",") + 1));
        };
        reader.onerror = () => reject(new Error("读取文件失败"));
        reader.readAsDataURL(file);
      });
      await saveProposal(skillId, proposal.headCommitSha, [
        { path: `assets/${file.name}`, contentBase64: base64 },
      ]);
      if (!mountedRef.current) return;
      setMessage(`已上传 assets/${file.name}`);
      await reloadProposal(skillId);
    } catch (uploadError) {
      if (mountedRef.current) setError(uploadError instanceof Error ? uploadError.message : "上传资产失败");
    } finally {
      if (mountedRef.current) setBusy("");
    }
  }

  function handleAddFile() {
    const path = newFilePath.trim();
    if (!path || !isTextPath(path)) {
      setError("新文件路径需以 references/ 开头且为文本文件（md/txt/json/yaml/yml）");
      return;
    }
    if (proposalPaths.includes(path)) {
      setError("该文件已存在");
      return;
    }
    setDrafts((current) => ({ ...current, [path]: "" }));
    setSelectedPath(path);
    setNewFilePath("");
    setError("");
    setMessage("");
  }

  async function handleToggleEnabled() {
    if (!guard("toggle-enabled") || !skill) return;
    try {
      const next = await setSkillEnabled(skillId, !skill.enabled, skill.revision);
      if (!mountedRef.current) return;
      setSkill(next);
      setMessage(next.enabled ? "已启用，聊天运行时会加载该 Skill" : "已停用");
    } catch (toggleError) {
      if (mountedRef.current) {
        setError(toggleError instanceof Error ? toggleError.message : "操作失败");
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

  async function handleArchiveRestore() {
    if (!guard("archive") || !skill) return;
    try {
      if (skill.archived) {
        const next = await restoreSkill(skillId, skill.revision);
        if (mountedRef.current) setSkill(next);
        if (mountedRef.current) setMessage("已恢复");
      } else {
        if (!window.confirm("确定归档该 Skill 吗？归档后不可在聊天中启用。")) {
          setBusy("");
          return;
        }
        const next = await archiveSkill(skillId, skill.revision);
        if (mountedRef.current) setSkill(next);
        if (mountedRef.current) setMessage("已归档");
      }
    } catch (archiveError) {
      if (mountedRef.current) {
        setError(archiveError instanceof Error ? archiveError.message : "操作失败");
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

  async function handleDeleteSkill() {
    if (!guard("delete-skill") || !skill) return;
    if (!window.confirm("确定彻底删除该 Skill 吗？该操作不可恢复。")) {
      setBusy("");
      return;
    }
    try {
      await deleteSkill(skillId);
      router.push("/me/skills");
    } catch (deleteError) {
      if (mountedRef.current) setError(deleteError instanceof Error ? deleteError.message : "删除失败");
      if (mountedRef.current) setBusy("");
    }
  }

  if (!authenticated) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  if (loading || !skill) {
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

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-4">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="text-sm text-amber-700" href="/me/skills">
            返回 Skill 列表
          </Link>
          <p className="mt-4 text-xs uppercase tracking-[0.28em] text-amber-700">Skill Workspace</p>
          <h1 className="mt-2 text-2xl font-semibold">{skill.displayName}</h1>
          <p className="mt-1 font-mono text-xs text-stone-500">{skill.skillKey}</p>
          {skill.description ? (
            <p className="mt-2 text-sm leading-6 text-stone-500">{skill.description}</p>
          ) : null}
          <div className="mt-3 flex flex-wrap gap-2">
            <span
              className={`rounded-full border px-3 py-1 text-xs font-medium ${skillStatusClassName(skill)}`}
            >
              {skillStatusLabel(skill)}
            </span>
            <span className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs text-stone-600">
              生效版本：{skill.activeVersion ? `v${skill.activeVersion}` : "未生效"}
            </span>
          </div>
        </header>

        {message ? (
          <p className="rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{message}</p>
        ) : null}
        {error ? <p className="rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-600">{error}</p> : null}

        <ProposalSection
          proposal={proposal}
          proposalMissing={proposalMissing}
          proposalPaths={proposalPaths}
          selectedPath={selectedPath}
          selectedContent={selectedContent}
          dirty={dirty}
          busy={busy}
          validation={validation}
          validatedCurrent={validatedCurrent}
          validatable={validatable}
          releaseNote={releaseNote}
          newFilePath={newFilePath}
          onCreate={() => void handleCreateProposal()}
          onSelectPath={(path) => setSelectedPath(path)}
          onContentChange={(content) => {
            if (!selectedPath) return;
            setDrafts((current) => ({ ...current, [selectedPath]: content }));
          }}
          onDiscardDraft={() => void handleDiscard()}
          onDeleteFile={(path) => void handleDeleteFile(path)}
          onNewFilePathChange={setNewFilePath}
          onAddFile={handleAddFile}
          onSave={() => void handleSave()}
          onValidate={() => void handleValidate()}
          onReleaseNoteChange={setReleaseNote}
          onPublish={() => undefined}
          onPublishSubmit={(event) => void handlePublish(event)}
          onAssetUpload={(file) => void handleAssetUpload(file)}
        />

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <div className="flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Releases</p>
              <h2 className="mt-1 text-lg font-semibold">版本历史（{releases.length}）</h2>
            </div>
          </div>

          {releases.length === 0 ? (
            <p className="mt-4 rounded-2xl bg-stone-50 px-4 py-5 text-sm leading-6 text-stone-500">
              还没有发布过版本。保存并校验草稿后即可发布第一个版本。
            </p>
          ) : (
            <div className="mt-4 space-y-3">
              {releases.map((release) => (
                <article
                  key={release.id}
                  className="rounded-2xl border border-stone-200 bg-stone-50/70 p-4"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <h3 className="text-sm font-semibold text-stone-800">
                        <Link
                          className="hover:text-amber-700"
                          href={`/me/skills/${skillId}/releases/${release.id}`}
                        >
                          v{release.versionNumber}
                          {skill.activeReleaseId === release.id ? " · 当前生效" : ""}
                        </Link>
                      </h3>
                      <p className="mt-1 break-words text-xs leading-5 text-stone-500">
                        {release.releaseNote || "（无版本说明）"}
                      </p>
                    </div>
                    <span
                      className={`shrink-0 rounded-full border px-3 py-1 text-xs font-medium ${
                        release.revoked
                          ? "border-red-200 bg-red-50 text-red-600"
                          : "border-stone-200 bg-white text-stone-600"
                      }`}
                    >
                      {release.revoked ? "已撤销" : "可用"}
                    </span>
                  </div>
                  <dl className="mt-3 grid grid-cols-2 gap-2 text-xs text-stone-500">
                    <div>
                      <dt>发布时间</dt>
                      <dd className="mt-0.5 text-stone-700">{formatDate(release.createdAt)}</dd>
                    </div>
                    <div>
                      <dt>Commit</dt>
                      <dd className="mt-0.5 font-mono text-stone-700">{shortCommit(release.commitSha)}</dd>
                    </div>
                  </dl>
                  <Link
                    className="mt-3 inline-block rounded-2xl border border-stone-300 bg-white px-3 py-2 text-xs font-semibold text-stone-700"
                    href={`/me/skills/${skillId}/releases/${release.id}`}
                  >
                    查看详情 / 设为生效
                  </Link>
                </article>
              ))}
            </div>
          )}
        </section>

        <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Settings</p>
          <h2 className="mt-1 text-lg font-semibold">设置</h2>
          <div className="mt-4 space-y-3">
            <button
              className="w-full rounded-2xl border border-stone-200 px-4 py-3 text-sm font-semibold text-stone-700 disabled:text-stone-300"
              type="button"
              disabled={busy !== "" || skill.archived}
              onClick={() => void handleToggleEnabled()}
            >
              {skill.enabled ? "停用（聊天不再加载）" : "启用（聊天运行时加载）"}
            </button>
            <button
              className="w-full rounded-2xl border border-stone-200 px-4 py-3 text-sm font-semibold text-stone-700 disabled:text-stone-300"
              type="button"
              disabled={busy !== ""}
              onClick={() => void handleArchiveRestore()}
            >
              {skill.archived ? "恢复 Skill" : "归档 Skill"}
            </button>
            {skill.activeReleaseId === null ? (
              <button
                className="w-full rounded-2xl border border-red-200 px-4 py-3 text-sm font-semibold text-red-600 disabled:text-red-300"
                type="button"
                disabled={busy !== ""}
                onClick={() => void handleDeleteSkill()}
              >
                彻底删除（仅未发布过版本的 Skill）
              </button>
            ) : null}
            <p className="text-xs leading-5 text-stone-500">
              启停、归档与生效版本是三个独立动作；发布不会自动改变生效版本。
            </p>
          </div>
        </section>
      </section>
    </main>
  );
}

type ProposalSectionProps = {
  proposal: Proposal | null;
  proposalMissing: boolean;
  proposalPaths: string[];
  selectedPath: string | null;
  selectedContent: string;
  dirty: boolean;
  busy: string;
  validation: ValidationOutcome | null;
  validatedCurrent: boolean;
  validatable: boolean;
  releaseNote: string;
  newFilePath: string;
  onCreate: () => void;
  onSelectPath: (path: string) => void;
  onContentChange: (content: string) => void;
  onDiscardDraft: () => void;
  onDeleteFile: (path: string) => void;
  onNewFilePathChange: (value: string) => void;
  onAddFile: () => void;
  onSave: () => void;
  onValidate: () => void;
  onReleaseNoteChange: (value: string) => void;
  onPublish: () => void;
  onPublishSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onAssetUpload: (file: File) => void;
};

function ProposalSection(props: ProposalSectionProps) {
  const {
    proposal,
    proposalMissing,
    proposalPaths,
    selectedPath,
    selectedContent,
    dirty,
    busy,
    validation,
    validatedCurrent,
    validatable,
    releaseNote,
    newFilePath,
  } = props;

  if (proposalMissing) {
    return (
      <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
        <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Proposal</p>
        <h2 className="mt-1 text-lg font-semibold">草稿</h2>
        <p className="mt-3 text-sm leading-6 text-stone-500">当前没有进行中的草稿。</p>
        <button
          className="mt-4 w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
          type="button"
          disabled={busy !== ""}
          onClick={props.onCreate}
        >
          {busy === "create-proposal" ? "创建中" : "从当前版本创建草稿"}
        </button>
      </section>
    );
  }

  if (!proposal) return null;

  return (
    <section className="space-y-4">
      <section className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Proposal</p>
            <h2 className="mt-1 text-lg font-semibold">草稿</h2>
          </div>
          <span className="shrink-0 rounded-full bg-white px-3 py-1 font-mono text-xs text-stone-500">
            {shortCommit(proposal.headCommitSha)}
          </span>
        </div>

        <div className="mt-4 space-y-2">
          {proposalPaths.map((path) => (
            <div key={path} className="flex items-center gap-2">
              <button
                className={`min-w-0 flex-1 truncate rounded-2xl border px-4 py-2.5 text-left font-mono text-xs ${
                  selectedPath === path
                    ? "border-stone-900 bg-stone-900 text-white"
                    : "border-stone-200 bg-stone-50/70 text-stone-700"
                }`}
                type="button"
                onClick={() => props.onSelectPath(path)}
              >
                {path}
              </button>
              <button
                className="shrink-0 rounded-2xl border border-red-200 px-3 py-2 text-xs font-semibold text-red-600 disabled:text-red-300"
                type="button"
                disabled={busy !== ""}
                onClick={() => props.onDeleteFile(path)}
              >
                删除
              </button>
            </div>
          ))}
        </div>

        <div className="mt-3 flex gap-2">
          <input
            className="min-w-0 flex-1 rounded-2xl border border-stone-200 bg-white px-4 py-2.5 font-mono text-xs outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
            value={newFilePath}
            onChange={(event) => props.onNewFilePathChange(event.target.value)}
            placeholder="references/new-file.md"
          />
          <button
            className="shrink-0 rounded-2xl border border-stone-300 bg-white px-4 py-2.5 text-xs font-semibold text-stone-700 disabled:text-stone-300"
            type="button"
            onClick={props.onAddFile}
          >
            新增文件
          </button>
        </div>

        <div className="mt-3">
          <label className="flex cursor-pointer items-center justify-center rounded-2xl border border-dashed border-stone-300 bg-stone-50/50 px-4 py-3 text-xs font-semibold text-stone-600">
            上传图片到 assets/
            <input
              className="hidden"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              disabled={busy !== ""}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                if (file) props.onAssetUpload(file);
              }}
            />
          </label>
        </div>

        {selectedPath ? (
          <div className="mt-4">
            <p className="mb-2 font-mono text-xs text-stone-500">{selectedPath}</p>
            <textarea
              className="min-h-64 w-full resize-y rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 font-mono text-xs leading-5 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
              value={selectedContent}
              onChange={(event) => props.onContentChange(event.target.value)}
              spellCheck={false}
            />
          </div>
        ) : null}

        <div className="mt-4 flex flex-wrap gap-2">
          <button
            className="flex-1 rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
            type="button"
            disabled={busy !== "" || !dirty}
            onClick={props.onSave}
          >
            {busy === "save" ? "保存中" : dirty ? "保存草稿" : "无未保存修改"}
          </button>
          <button
            className="flex-1 rounded-2xl border border-stone-300 bg-white px-4 py-3 text-sm font-semibold text-stone-700 disabled:text-stone-300"
            type="button"
            disabled={busy !== "" || !validatable}
            onClick={props.onValidate}
          >
            {busy === "validate" ? "校验中" : "运行校验"}
          </button>
          <button
            className="w-full rounded-2xl border border-red-200 px-4 py-3 text-sm font-semibold text-red-600 disabled:text-red-300"
            type="button"
            disabled={busy !== ""}
            onClick={props.onDiscardDraft}
          >
            放弃草稿
          </button>
        </div>

        {dirty ? (
          <p className="mt-3 rounded-2xl bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-700">
            有未保存修改：请先保存再校验；校验结果只对保存后的内容有效。
          </p>
        ) : null}

        {validation ? (
          <div className="mt-3 space-y-2">
            <p
              className={`rounded-2xl px-4 py-3 text-xs leading-5 ${
                validation.valid ? "bg-emerald-50 text-emerald-700" : "bg-red-50 text-red-600"
              }`}
            >
              {validation.valid
                ? validatedCurrent
                  ? "校验通过，可以发布。"
                  : "校验结果已过期（草稿已再次保存），请重新校验。"
                : "校验未通过，请修正以下错误后重试："}
            </p>
            {validation.errors.length > 0 ? (
              <ul className="list-inside list-disc space-y-1 rounded-2xl bg-red-50 px-4 py-3 text-xs leading-5 text-red-600">
                {validation.errors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            ) : null}
            {validation.warnings.length > 0 ? (
              <ul className="list-inside list-disc space-y-1 rounded-2xl bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-700">
                {validation.warnings.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </section>

      <form
        className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur"
        onSubmit={props.onPublishSubmit}
      >
        <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Publish</p>
        <h2 className="mt-1 text-lg font-semibold">发布新版本</h2>
        <p className="mt-2 text-xs leading-5 text-stone-500">
          发布只生成不可变的新版本，不会改变当前生效版本，也不会自动启用；需要生效请在版本详情中单独操作。
        </p>
        <input
          className="mt-3 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
          value={releaseNote}
          onChange={(event) => props.onReleaseNoteChange(event.target.value)}
          maxLength={200}
          placeholder="简短版本说明，例如：补充 references/引用"
        />
        <button
          className="mt-3 w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:bg-stone-400"
          type="submit"
          disabled={busy !== "" || !validatedCurrent || !validation?.valid || !releaseNote.trim()}
        >
          {busy === "publish" ? "发布中" : "发布（不改变生效版本）"}
        </button>
      </form>
    </section>
  );
}

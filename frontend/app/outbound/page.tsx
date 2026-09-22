"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import {
  ContactImportRow,
  DraftIdentity,
  OutboundContact,
  OutboundTaskDetail,
  OutboundTaskSummary,
  PhoneAgent,
  TaskDraft,
  createOutboundTask,
  getOutboundTask,
  identityForDraft,
  importContacts,
  listContacts,
  listOutboundTasks,
  listPhoneAgents,
  markContactDnc,
  parseContactRows,
  reconcileOutboundCall,
  resolveUnknownCall,
  startOutboundTask,
  stopOutboundTask,
} from "@/lib/outbound";

const taskLabels: Record<string, string> = {
  READY: "待启动", RUNNING: "运行中", PAUSED: "已暂停", STOPPED: "已停止", COMPLETED: "已完成",
};
const stageLabels: Record<string, string> = {
  QUEUED: "待拨打", PREPARING: "准备中", DIALING: "呼叫中", ACTIVE: "通话中",
  ENDING: "结束确认中", UNKNOWN: "状态待核实", FINISHED: "已结案", CANCELLED: "已取消",
};

function tone(value: string) {
  if (["RUNNING", "ACTIVE", "DIALING"].includes(value)) return "bg-emerald-50 text-emerald-700";
  if (["PAUSED", "UNKNOWN", "ENDING", "PREPARING"].includes(value)) return "bg-amber-50 text-amber-700";
  if (value === "STOPPED") return "bg-red-50 text-red-700";
  return "bg-stone-100 text-stone-600";
}

function Badge({ value, label }: { value: string; label?: string }) {
  return <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${tone(value)}`}>{label ?? value}</span>;
}

function errorText(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

export default function OutboundPage() {
  const router = useRouter();
  const [contacts, setContacts] = useState<OutboundContact[]>([]);
  const [agents, setAgents] = useState<PhoneAgent[]>([]);
  const [tasks, setTasks] = useState<OutboundTaskSummary[]>([]);
  const [selectedContacts, setSelectedContacts] = useState<number[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [detail, setDetail] = useState<OutboundTaskDetail | null>(null);
  const [importText, setImportText] = useState("");
  const [taskName, setTaskName] = useState("");
  const [goal, setGoal] = useState("");
  const [agentId, setAgentId] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const identity = useRef<DraftIdentity | null>(null);

  async function reloadOverview() {
    const [contactPage, agentPage, taskPage] = await Promise.all([
      listContacts(), listPhoneAgents(), listOutboundTasks(),
    ]);
    setContacts(contactPage.items);
    setAgents(agentPage.items);
    setTasks(taskPage.items);
    setAgentId((current) => current || agentPage.items[0]?.id || "");
  }

  useEffect(() => {
    getCurrentUser()
      .then(reloadOverview)
      .catch((loadError) => {
        if (loadError instanceof Error && /登录|Unauthorized/i.test(loadError.message)) {
          savePostLoginRedirect("/outbound");
          router.replace("/auth/login");
          return;
        }
        setError(errorText(loadError, "外呼数据加载失败"));
      })
      .finally(() => setLoading(false));
  }, [router]);

  useEffect(() => {
    if (selectedTaskId == null) return;
    let active = true;
    const load = () => getOutboundTask(selectedTaskId)
      .then((value) => { if (active) setDetail(value); })
      .catch((loadError) => { if (active) setError(errorText(loadError, "任务详情加载失败")); });
    void load();
    const timer = window.setInterval(load, 2_500);
    return () => { active = false; window.clearInterval(timer); };
  }, [selectedTaskId]);

  async function handleImport(event: FormEvent) {
    event.preventDefault();
    setError("");
    setNotice("");
    let rows: ContactImportRow[];
    try {
      rows = parseContactRows(importText);
      if (!rows.length || rows.some((row) => !row.phone || !row.consentBasis)) {
        throw new Error("每行都需要填写分机、姓名和授权依据；姓名可留空但仍需保留分隔符");
      }
    } catch (parseError) {
      setError(errorText(parseError, "名单格式错误"));
      return;
    }
    setBusy("import");
    try {
      const result = await importContacts(rows);
      setNotice(`已导入 ${result.inserted} 条；重复号码保持原禁呼状态。`);
      setImportText("");
      const page = await listContacts();
      setContacts(page.items);
    } catch (submitError) {
      setError(errorText(submitError, "导入失败"));
    } finally {
      setBusy("");
    }
  }

  async function handleDnc(contact: OutboundContact) {
    if (!window.confirm(`将 ${contact.phone} 标记为禁呼？此操作不会被重复导入解除。`)) return;
    setBusy(`dnc-${contact.id}`);
    setError("");
    try {
      await markContactDnc(contact.id);
      setContacts((current) => current.map((item) => item.id === contact.id ? { ...item, dnc: true } : item));
      setSelectedContacts((current) => current.filter((id) => id !== contact.id));
    } catch (updateError) {
      setError(errorText(updateError, "禁呼更新失败"));
    } finally {
      setBusy("");
    }
  }

  async function handleCreateTask(event: FormEvent) {
    event.preventDefault();
    const draft: TaskDraft = { name: taskName.trim(), agentId, communicationGoal: goal.trim(), contactIds: selectedContacts };
    identity.current = identityForDraft(identity.current, draft);
    setBusy("create");
    setError("");
    setNotice("");
    try {
      const created = await createOutboundTask(draft, identity.current.requestId);
      setNotice("任务已创建，启动前不会产生网络拨号。");
      setTaskName("");
      setGoal("");
      setSelectedContacts([]);
      identity.current = null;
      setSelectedTaskId(created.id);
      setDetail(created);
      setTasks((await listOutboundTasks()).items);
    } catch (createError) {
      setError(errorText(createError, "任务创建失败；再次提交会沿用相同 requestId"));
    } finally {
      setBusy("");
    }
  }

  async function changeTask(action: "start" | "stop", taskId: number) {
    setBusy(`${action}-${taskId}`);
    setError("");
    try {
      if (action === "start") await startOutboundTask(taskId);
      else await stopOutboundTask(taskId);
      setNotice(action === "start"
        ? "任务已进入运行队列。"
        : "已取消剩余条目并请求结束当前电话，详情可能暂时显示结束确认中。");
      const [taskPage, taskDetail] = await Promise.all([listOutboundTasks(), getOutboundTask(taskId)]);
      setTasks(taskPage.items);
      setDetail(taskDetail);
    } catch (actionError) {
      setError(errorText(actionError, action === "start" ? "启动失败" : "停止失败"));
    } finally {
      setBusy("");
    }
  }

  async function reconcile(callId: number) {
    setBusy(`reconcile-${callId}`);
    setError("");
    try {
      await reconcileOutboundCall(callId);
      if (selectedTaskId != null) setDetail(await getOutboundTask(selectedTaskId));
    } catch (reconcileError) {
      setError(errorText(reconcileError, "重查失败"));
    } finally {
      setBusy("");
    }
  }

  async function resolve(callId: number) {
    const resolution = window.prompt("结案类型：ANSWERED、NO_ANSWER 或 FAILED", "FAILED")?.trim().toUpperCase();
    if (!resolution) return;
    if (!["ANSWERED", "NO_ANSWER", "FAILED"].includes(resolution)) {
      setError("结案类型只能是 ANSWERED、NO_ANSWER 或 FAILED");
      return;
    }
    const note = window.prompt("请填写已经人工核实远端通道与旧执行均已结束的备注：")?.trim();
    if (!note) return;
    if (!window.confirm("确认将该 UNKNOWN 通话人工结案？")) return;
    setBusy(`resolve-${callId}`);
    setError("");
    try {
      await resolveUnknownCall(callId, resolution, note);
      if (selectedTaskId != null) setDetail(await getOutboundTask(selectedTaskId));
      setTasks((await listOutboundTasks()).items);
    } catch (resolveError) {
      setError(errorText(resolveError, "人工结案失败"));
    } finally {
      setBusy("");
    }
  }

  if (loading) return <main className="min-h-screen bg-[#f7f4ea]" />;

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <div className="mx-auto max-w-6xl space-y-5">
        <header className="flex flex-col gap-4 rounded-[2rem] border border-stone-200/80 bg-white/90 p-6 shadow-[0_24px_60px_rgba(76,59,36,0.12)] sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-xs uppercase tracking-[0.28em] text-amber-700">Softphone outbound</p>
            <h1 className="mt-2 text-3xl font-semibold">外呼任务</h1>
            <p className="mt-2 max-w-2xl text-sm text-stone-500">仅允许配置中的测试分机。启动后全局一次只处理一通电话。</p>
          </div>
          <Link href="/me" className="text-sm font-semibold text-amber-700">返回我的 →</Link>
        </header>

        {error ? <p role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</p> : null}
        {notice ? <p className="rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{notice}</p> : null}

        <div className="grid gap-5 lg:grid-cols-[0.9fr_1.1fr]">
          <section className="space-y-5">
            <form onSubmit={handleImport} className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-sm">
              <h2 className="text-lg font-semibold">1. 导入授权名单</h2>
              <p className="mt-1 text-xs text-stone-500">每行：分机, 姓名, 授权依据；也支持 Tab 分隔，最多 100 条。</p>
              <textarea required rows={5} value={importText} onChange={(event) => setImportText(event.target.value)}
                placeholder="1001, 张三, 2026-09-21 书面授权"
                className="mt-4 w-full rounded-2xl border border-stone-200 bg-stone-50 px-4 py-3 text-sm outline-none focus:border-amber-500" />
              <button disabled={busy === "import"} className="mt-3 rounded-xl bg-stone-900 px-5 py-2.5 text-sm font-semibold text-white disabled:opacity-50">
                {busy === "import" ? "导入中…" : "导入名单"}
              </button>
            </form>

            <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-sm">
              <div className="flex items-center justify-between">
                <h2 className="text-lg font-semibold">名单</h2>
                <span className="text-xs text-stone-400">已选 {selectedContacts.length}</span>
              </div>
              <div className="mt-3 max-h-[26rem] space-y-2 overflow-y-auto pr-1">
                {contacts.length === 0 ? <p className="py-8 text-center text-sm text-stone-400">尚未导入联系人</p> : contacts.map((contact) => (
                  <div key={contact.id} className="flex items-start gap-3 rounded-2xl border border-stone-100 p-3">
                    <input type="checkbox" className="mt-1 size-4 accent-amber-600" disabled={contact.dnc}
                      checked={selectedContacts.includes(contact.id)} onChange={(event) => setSelectedContacts((current) =>
                        event.target.checked ? [...current, contact.id] : current.filter((id) => id !== contact.id))} />
                    <div className="min-w-0 flex-1">
                      <p className="font-semibold">{contact.phone} <span className="font-normal text-stone-500">{contact.name}</span></p>
                      <p className="mt-1 break-words text-xs text-stone-400">{contact.consentBasis}</p>
                    </div>
                    {contact.dnc ? <Badge value="STOPPED" label="禁呼" /> : (
                      <button type="button" disabled={busy === `dnc-${contact.id}`} onClick={() => void handleDnc(contact)}
                        className="text-xs font-semibold text-red-600 disabled:opacity-50">标记禁呼</button>
                    )}
                  </div>
                ))}
              </div>
            </div>
          </section>

          <section className="space-y-5">
            <form onSubmit={handleCreateTask} className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-sm">
              <h2 className="text-lg font-semibold">2. 创建任务</h2>
              {agents.length === 0 ? <p className="mt-3 rounded-xl bg-amber-50 p-3 text-sm text-amber-700">当前没有已验证的电话 Agent。</p> : null}
              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                <label className="text-xs font-semibold text-stone-500">任务名称
                  <input required maxLength={256} value={taskName} onChange={(event) => setTaskName(event.target.value)}
                    className="mt-1 w-full rounded-xl border border-stone-200 px-3 py-2.5 text-sm text-stone-900 outline-none focus:border-amber-500" />
                </label>
                <label className="text-xs font-semibold text-stone-500">电话 Agent
                  <select required value={agentId} onChange={(event) => setAgentId(event.target.value)}
                    className="mt-1 w-full rounded-xl border border-stone-200 px-3 py-2.5 text-sm text-stone-900 outline-none focus:border-amber-500">
                    {agents.map((agent) => <option value={agent.id} key={agent.id}>{agent.name}</option>)}
                  </select>
                </label>
              </div>
              <label className="mt-3 block text-xs font-semibold text-stone-500">沟通目标
                <textarea required maxLength={1024} rows={3} value={goal} onChange={(event) => setGoal(event.target.value)}
                  className="mt-1 w-full rounded-xl border border-stone-200 px-3 py-2.5 text-sm text-stone-900 outline-none focus:border-amber-500" />
              </label>
              <button disabled={busy === "create" || !agents.length || !selectedContacts.length}
                className="mt-4 rounded-xl bg-amber-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-amber-600/20 disabled:opacity-50">
                {busy === "create" ? "创建中…" : `创建任务（${selectedContacts.length} 个分机）`}
              </button>
            </form>

            <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-sm">
              <h2 className="text-lg font-semibold">3. 任务与进度</h2>
              <div className="mt-3 space-y-2">
                {tasks.length === 0 ? <p className="py-8 text-center text-sm text-stone-400">尚未创建任务</p> : tasks.map((task) => (
                  <button type="button" key={task.id} onClick={() => { setDetail(null); setSelectedTaskId(task.id); }}
                    className={`w-full rounded-2xl border p-4 text-left ${selectedTaskId === task.id ? "border-amber-400 bg-amber-50/50" : "border-stone-100"}`}>
                    <div className="flex items-center justify-between gap-3">
                      <span className="font-semibold">{task.name}</span>
                      <Badge value={task.status} label={taskLabels[task.status] ?? task.status} />
                    </div>
                    <p className="mt-2 text-xs text-stone-500">共 {task.total} 通 · 已结案 {task.stageCounts.FINISHED ?? 0} · 待拨 {task.stageCounts.QUEUED ?? 0}</p>
                    {task.statusReason ? <p className="mt-1 text-xs text-amber-700">{task.statusReason}</p> : null}
                  </button>
                ))}
              </div>
            </div>
          </section>
        </div>

        {detail ? (
          <section className="rounded-[2rem] border border-stone-200/80 bg-white/95 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.10)]">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div>
                <div className="flex items-center gap-3"><h2 className="text-xl font-semibold">{detail.name}</h2><Badge value={detail.status} label={taskLabels[detail.status]} /></div>
                <p className="mt-2 text-sm text-stone-500">{detail.communicationGoal}</p>
                <p className="mt-1 font-mono text-[11px] text-stone-400">requestId: {detail.requestId}</p>
              </div>
              <div className="flex gap-2">
                {(["READY", "PAUSED"] as string[]).includes(detail.status) ? (
                  <button disabled={busy === `start-${detail.id}`} onClick={() => void changeTask("start", detail.id)} className="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">启动</button>
                ) : null}
                {detail.status === "RUNNING" ? (
                  <button disabled={busy === `stop-${detail.id}`} onClick={() => void changeTask("stop", detail.id)} className="rounded-xl bg-red-600 px-4 py-2 text-sm font-semibold text-white disabled:opacity-50">停止任务</button>
                ) : null}
                <button type="button" onClick={() => { setSelectedTaskId(null); setDetail(null); }} className="rounded-xl border border-stone-200 px-4 py-2 text-sm font-semibold">关闭详情</button>
              </div>
            </div>
            <div className="mt-5 overflow-x-auto">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="border-b border-stone-200 text-xs uppercase tracking-wider text-stone-400"><tr>
                  <th className="px-3 py-3">联系人</th><th className="px-3 py-3">状态</th><th className="px-3 py-3">接通</th><th className="px-3 py-3">对话</th><th className="px-3 py-3">说明</th><th className="px-3 py-3">操作</th>
                </tr></thead>
                <tbody>{detail.calls.map((call) => (
                  <tr key={call.id} className="border-b border-stone-100 align-top">
                    <td className="px-3 py-4 font-semibold">{call.phone}<span className="ml-2 font-normal text-stone-400">{call.name}</span></td>
                    <td className="px-3 py-4"><Badge value={call.stage} label={stageLabels[call.stage] ?? call.stage} /></td>
                    <td className="px-3 py-4 text-stone-600">{call.connectResult || "—"}</td>
                    <td className="px-3 py-4 text-stone-600">{call.dialogueResult || "—"}
                      {call.sessionId ? <Link className="mt-1 block text-xs font-semibold text-amber-700" href={`/chat?sessionId=${encodeURIComponent(call.sessionId)}`}>查看会话 →</Link> : null}
                    </td>
                    <td className="max-w-xs px-3 py-4 text-xs text-stone-500">{call.reason || "—"}</td>
                    <td className="px-3 py-4">
                      {call.stage === "UNKNOWN" ? <div className="flex gap-3">
                        <button disabled={busy === `reconcile-${call.id}`} onClick={() => void reconcile(call.id)} className="font-semibold text-amber-700 disabled:opacity-50">重查</button>
                        <button disabled={busy === `resolve-${call.id}`} onClick={() => void resolve(call.id)} className="font-semibold text-red-600 disabled:opacity-50">人工结案</button>
                      </div> : "—"}
                    </td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </section>
        ) : null}
      </div>
    </main>
  );
}

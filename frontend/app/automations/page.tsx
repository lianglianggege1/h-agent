"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { AgentSummary, listAgents } from "@/lib/agents";
import {
  AutomationRun,
  AutomationTask,
  AutomationTaskInput,
  createAutomation,
  deleteAutomation,
  listAutomationRuns,
  listAutomations,
  runAutomation,
  runtimeForAgent,
  updateAutomation,
} from "@/lib/automations";
import { savePostLoginRedirect } from "@/lib/session";

type Frequency = "daily" | "weekdays" | "weekly" | "custom";

const WEEKDAYS = [
  { value: "1", label: "周一" },
  { value: "2", label: "周二" },
  { value: "3", label: "周三" },
  { value: "4", label: "周四" },
  { value: "5", label: "周五" },
  { value: "6", label: "周六" },
  { value: "0", label: "周日" },
];

function formatDate(value: string | null) {
  if (!value) return "尚未执行";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function cronFor(frequency: Frequency, time: string, weekday: string, customCron: string) {
  if (frequency === "custom") return customCron.trim();
  const [hour, minute] = time.split(":");
  if (frequency === "weekdays") return `0 ${Number(minute)} ${Number(hour)} * * 1-5`;
  if (frequency === "weekly") return `0 ${Number(minute)} ${Number(hour)} * * ${weekday}`;
  return `0 ${Number(minute)} ${Number(hour)} * * *`;
}

function scheduleLabel(task: AutomationTask) {
  const parts = task.cronExpression.split(/\s+/);
  if (parts.length === 6 && parts[0] === "0") {
    const time = `${parts[2].padStart(2, "0")}:${parts[1].padStart(2, "0")}`;
    if (parts[5] === "*") return `每天 ${time}`;
    if (parts[5] === "1-5") return `工作日 ${time}`;
    const day = WEEKDAYS.find((item) => item.value === parts[5]);
    if (day) return `每${day.label} ${time}`;
  }
  return task.cronExpression;
}

export default function AutomationsPage() {
  const router = useRouter();
  const [tasks, setTasks] = useState<AutomationTask[]>([]);
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [runs, setRuns] = useState<Record<string, AutomationRun[]>>({});
  const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const [error, setError] = useState("");

  const [name, setName] = useState("");
  const [instruction, setInstruction] = useState("");
  const [agentId, setAgentId] = useState("");
  const [frequency, setFrequency] = useState<Frequency>("daily");
  const [time, setTime] = useState("09:00");
  const [weekday, setWeekday] = useState("1");
  const [customCron, setCustomCron] = useState("0 0 9 * * *");
  const zoneId = useMemo(() => Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Shanghai", []);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        const [taskList, agentList] = await Promise.all([listAutomations(), listAgents()]);
        setTasks(taskList);
        setAgents(agentList);
        setAgentId(agentList[0]?.agentId ?? "");
      })
      .catch((loadError) => {
        if (loadError instanceof Error && /登录|Unauthorized/i.test(loadError.message)) {
          savePostLoginRedirect("/automations");
          router.replace("/auth/login");
          return;
        }
        setError(loadError instanceof Error ? loadError.message : "加载自动化任务失败");
      })
      .finally(() => setLoading(false));
  }, [router]);

  const selectedAgent = agents.find((agent) => agent.agentId === agentId);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!selectedAgent) return;
    setSaving(true);
    setError("");
    try {
      const created = await createAutomation({
        name,
        instruction,
        agentId: selectedAgent.agentId,
        runtime: runtimeForAgent(selectedAgent.runtimeType),
        cronExpression: cronFor(frequency, time, weekday, customCron),
        zoneId,
        enabled: true,
      });
      setTasks((current) => [created, ...current]);
      setName("");
      setInstruction("");
      setShowCreate(false);
    } catch (submitError) {
      setError(submitError instanceof Error ? submitError.message : "创建失败");
    } finally {
      setSaving(false);
    }
  }

  function inputOf(task: AutomationTask, enabled = task.enabled): AutomationTaskInput {
    return {
      name: task.name,
      instruction: task.instruction,
      agentId: task.agentId,
      runtime: task.runtime,
      cronExpression: task.cronExpression,
      zoneId: task.zoneId,
      enabled,
      expectedRevision: task.revision,
    };
  }

  async function toggle(task: AutomationTask) {
    setBusyTaskId(task.id);
    setError("");
    try {
      const updated = await updateAutomation(task.id, inputOf(task, !task.enabled));
      setTasks((current) => current.map((item) => (item.id === task.id ? updated : item)));
    } catch (updateError) {
      setError(updateError instanceof Error ? updateError.message : "更新失败");
    } finally {
      setBusyTaskId(null);
    }
  }

  async function remove(task: AutomationTask) {
    if (!window.confirm(`删除自动化任务“${task.name}”？运行历史将保留在数据库中。`)) return;
    setBusyTaskId(task.id);
    try {
      await deleteAutomation(task.id);
      setTasks((current) => current.filter((item) => item.id !== task.id));
    } catch (deleteError) {
      setError(deleteError instanceof Error ? deleteError.message : "删除失败");
    } finally {
      setBusyTaskId(null);
    }
  }

  async function runNow(task: AutomationTask) {
    setBusyTaskId(task.id);
    setError("");
    try {
      const run = await runAutomation(task.id);
      setRuns((current) => ({ ...current, [task.id]: [run, ...(current[task.id] ?? [])] }));
      setExpandedTaskId(task.id);
    } catch (runError) {
      setError(runError instanceof Error ? runError.message : "启动失败");
    } finally {
      setBusyTaskId(null);
    }
  }

  async function toggleRuns(taskId: string) {
    if (expandedTaskId === taskId) {
      setExpandedTaskId(null);
      return;
    }
    setExpandedTaskId(taskId);
    try {
      const history = await listAutomationRuns(taskId);
      setRuns((current) => ({ ...current, [taskId]: history }));
    } catch (runsError) {
      setError(runsError instanceof Error ? runsError.message : "加载运行历史失败");
    }
  }

  return (
    <main className="min-h-screen bg-[radial-gradient(circle_at_top_left,#fff9df_0%,transparent_35%),linear-gradient(145deg,#f7f4ea_0%,#eee7d8_100%)] text-stone-900">
      <section className="mx-auto min-h-screen w-full max-w-6xl px-5 pb-16 pt-8 sm:px-8">
        <header className="flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="mb-3 flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.2em] text-amber-700">
              <span className="h-2 w-2 rounded-full bg-amber-500" /> Automation desk
            </div>
            <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">自动化任务</h1>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-stone-500">
              让 LangChain4j 或 AgentScope 按计划独立工作。每次运行都会生成可追溯的聊天会话。
            </p>
          </div>
          <div className="flex items-center gap-3">
            <Link className="rounded-xl px-4 py-2.5 text-sm font-semibold text-stone-600 hover:bg-white/70" href="/chat">
              返回聊天
            </Link>
            <button
              type="button"
              className="rounded-xl bg-stone-900 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-stone-900/15 transition hover:-translate-y-0.5"
              onClick={() => setShowCreate(true)}
            >
              ＋ 新建任务
            </button>
          </div>
        </header>

        <div className="mt-8 grid gap-4 sm:grid-cols-3">
          {[
            ["任务总数", tasks.length],
            ["运行中", tasks.filter((task) => task.enabled).length],
            ["最近失败", tasks.filter((task) => task.lastStatus === "FAILED").length],
          ].map(([label, value]) => (
            <div key={label} className="rounded-2xl border border-white/70 bg-white/65 p-5 shadow-sm backdrop-blur">
              <p className="text-xs font-medium text-stone-500">{label}</p>
              <p className="mt-2 text-3xl font-semibold tabular-nums">{value}</p>
            </div>
          ))}
        </div>

        {error ? <p className="mt-5 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</p> : null}

        <div className="mt-6 space-y-4">
          {loading ? (
            <div className="rounded-2xl border border-stone-200 bg-white/75 p-6 text-sm text-stone-500">正在加载…</div>
          ) : tasks.length === 0 ? (
            <div className="rounded-3xl border border-dashed border-stone-300 bg-white/55 px-6 py-16 text-center">
              <p className="text-lg font-semibold">还没有自动化任务</p>
              <p className="mt-2 text-sm text-stone-500">从这里创建，或者在聊天里告诉 Agent “每天几点帮我做什么”。</p>
              <button className="mt-6 rounded-xl bg-amber-600 px-5 py-2.5 text-sm font-semibold text-white" onClick={() => setShowCreate(true)}>
                创建第一个任务
              </button>
            </div>
          ) : tasks.map((task) => (
            <article key={task.id} className="overflow-hidden rounded-2xl border border-stone-200/80 bg-white/85 shadow-sm">
              <div className="p-5 sm:p-6">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="text-lg font-semibold">{task.name}</h2>
                      <span className={`rounded-full px-2.5 py-1 text-[11px] font-semibold ${task.runtime === "AGENTSCOPE" ? "bg-violet-100 text-violet-700" : "bg-sky-100 text-sky-700"}`}>
                        {task.runtime === "AGENTSCOPE" ? "AgentScope" : "LangChain4j"}
                      </span>
                      <span className="rounded-full bg-stone-100 px-2.5 py-1 text-[11px] text-stone-500">{task.agentId}</span>
                    </div>
                    <p className="mt-3 line-clamp-2 max-w-3xl text-sm leading-6 text-stone-600">{task.instruction}</p>
                  </div>
                  <button
                    type="button"
                    disabled={busyTaskId === task.id}
                    onClick={() => toggle(task)}
                    className={`relative h-7 w-12 shrink-0 rounded-full transition ${task.enabled ? "bg-emerald-500" : "bg-stone-300"}`}
                    aria-label={task.enabled ? "暂停任务" : "启用任务"}
                  >
                    <span className={`absolute top-1 h-5 w-5 rounded-full bg-white shadow transition ${task.enabled ? "left-6" : "left-1"}`} />
                  </button>
                </div>

                <div className="mt-5 grid gap-3 border-t border-stone-100 pt-4 text-sm sm:grid-cols-3">
                  <div><span className="text-stone-400">计划</span><p className="mt-1 font-medium">{scheduleLabel(task)}</p></div>
                  <div><span className="text-stone-400">下次执行</span><p className="mt-1 font-medium">{task.enabled ? formatDate(task.nextRunAt) : "已暂停"}</p></div>
                  <div><span className="text-stone-400">最近状态</span><p className={`mt-1 font-medium ${task.lastStatus === "FAILED" ? "text-red-600" : "text-stone-800"}`}>{task.lastStatus ?? "尚未执行"}</p></div>
                </div>

                <div className="mt-5 flex flex-wrap items-center gap-2">
                  <button className="rounded-lg bg-stone-900 px-3.5 py-2 text-xs font-semibold text-white disabled:opacity-50" disabled={busyTaskId === task.id} onClick={() => runNow(task)}>立即运行</button>
                  <button className="rounded-lg border border-stone-200 px-3.5 py-2 text-xs font-semibold text-stone-600" onClick={() => toggleRuns(task.id)}>运行历史</button>
                  <button className="ml-auto rounded-lg px-3 py-2 text-xs font-semibold text-red-600 hover:bg-red-50" disabled={busyTaskId === task.id} onClick={() => remove(task)}>删除</button>
                </div>
              </div>

              {expandedTaskId === task.id ? (
                <div className="border-t border-stone-200 bg-stone-50/80 px-5 py-4 sm:px-6">
                  <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-stone-400">最近运行</p>
                  {(runs[task.id] ?? []).length === 0 ? <p className="text-sm text-stone-500">暂无运行记录。</p> : (
                    <div className="space-y-2">
                      {(runs[task.id] ?? []).map((run) => (
                        <div key={run.id} className="flex flex-col gap-2 rounded-xl bg-white p-3 text-xs sm:flex-row sm:items-center">
                          <span className={`font-semibold ${run.status === "FAILED" ? "text-red-600" : run.status === "RUNNING" ? "text-amber-600" : "text-emerald-600"}`}>{run.status}</span>
                          <span className="text-stone-400">{run.triggerType === "MANUAL" ? "手动" : "计划"} · {formatDate(run.startedAt)}</span>
                          <span className="line-clamp-1 flex-1 text-stone-600">{run.errorMessage ?? run.output ?? "执行中…"}</span>
                          {run.sessionId ? <Link className="font-semibold text-amber-700" href={`/chat?sessionId=${encodeURIComponent(run.sessionId)}`}>打开会话 →</Link> : null}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ) : null}
            </article>
          ))}
        </div>
      </section>

      {showCreate ? (
        <div className="fixed inset-0 z-50 flex items-end justify-center bg-stone-900/45 p-0 backdrop-blur-sm sm:items-center sm:p-6" onMouseDown={(event) => event.target === event.currentTarget && setShowCreate(false)}>
          <form className="max-h-[94vh] w-full max-w-2xl overflow-y-auto rounded-t-3xl bg-[#fffdf8] p-6 shadow-2xl sm:rounded-3xl sm:p-8" onSubmit={submit}>
            <div className="flex items-center justify-between">
              <div><p className="text-xs font-semibold uppercase tracking-[0.16em] text-amber-700">New automation</p><h2 className="mt-1 text-2xl font-semibold">新建自动化任务</h2></div>
              <button type="button" className="h-10 w-10 rounded-full text-xl text-stone-500 hover:bg-stone-100" onClick={() => setShowCreate(false)}>×</button>
            </div>

            <div className="mt-7 space-y-5">
              <label className="block text-sm font-semibold">任务名称
                <input required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} placeholder="例如：每日 AI 行业简报" className="mt-2 h-12 w-full rounded-xl border border-stone-200 bg-white px-4 font-normal outline-none focus:border-amber-500 focus:ring-4 focus:ring-amber-100" />
              </label>
              <label className="block text-sm font-semibold">选择 Agent
                <select required value={agentId} onChange={(event) => setAgentId(event.target.value)} className="mt-2 h-12 w-full rounded-xl border border-stone-200 bg-white px-4 font-normal outline-none focus:border-amber-500">
                  {agents.map((agent) => <option value={agent.agentId} key={agent.agentId}>{agent.displayName} · {runtimeForAgent(agent.runtimeType) === "AGENTSCOPE" ? "AgentScope" : "LangChain4j"}</option>)}
                </select>
              </label>
              <label className="block text-sm font-semibold">你希望 Agent 做什么？
                <textarea required maxLength={20000} value={instruction} onChange={(event) => setInstruction(event.target.value)} placeholder="描述每次触发时需要完成的任务、输入来源和期望输出…" className="mt-2 min-h-32 w-full resize-y rounded-xl border border-stone-200 bg-white px-4 py-3 font-normal leading-6 outline-none focus:border-amber-500 focus:ring-4 focus:ring-amber-100" />
              </label>

              <fieldset>
                <legend className="text-sm font-semibold">触发时间</legend>
                <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
                  {([[
                    "daily", "每天"], ["weekdays", "工作日"], ["weekly", "每周"], ["custom", "自定义 Cron"]] as [Frequency, string][]).map(([value, label]) => (
                    <button key={value} type="button" onClick={() => setFrequency(value)} className={`rounded-xl border px-3 py-2.5 text-sm font-medium ${frequency === value ? "border-stone-900 bg-stone-900 text-white" : "border-stone-200 bg-white text-stone-600"}`}>{label}</button>
                  ))}
                </div>
                {frequency === "custom" ? (
                  <input required value={customCron} onChange={(event) => setCustomCron(event.target.value)} className="mt-3 h-12 w-full rounded-xl border border-stone-200 bg-white px-4 font-mono text-sm outline-none focus:border-amber-500" />
                ) : (
                  <div className="mt-3 grid gap-3 sm:grid-cols-2">
                    {frequency === "weekly" ? <select value={weekday} onChange={(event) => setWeekday(event.target.value)} className="h-12 rounded-xl border border-stone-200 bg-white px-4">{WEEKDAYS.map((day) => <option key={day.value} value={day.value}>{day.label}</option>)}</select> : <div className="hidden sm:block" />}
                    <input required type="time" value={time} onChange={(event) => setTime(event.target.value)} className="h-12 rounded-xl border border-stone-200 bg-white px-4" />
                  </div>
                )}
                <p className="mt-2 text-xs text-stone-400">时区：{zoneId} · 最短执行间隔 1 分钟</p>
              </fieldset>
            </div>

            <div className="mt-8 flex justify-end gap-3">
              <button type="button" className="rounded-xl px-5 py-2.5 text-sm font-semibold text-stone-600" onClick={() => setShowCreate(false)}>取消</button>
              <button type="submit" disabled={saving || !agentId} className="rounded-xl bg-amber-600 px-6 py-2.5 text-sm font-semibold text-white shadow-lg shadow-amber-600/20 disabled:opacity-50">{saving ? "创建中…" : "创建任务"}</button>
            </div>
          </form>
        </div>
      ) : null}
    </main>
  );
}

import { chatChartSchema, type ChatChart } from "./chat-chart.ts";

export type TeamConfig = {
  homeserverUrl: string;
  roomId: string;
  managerUserId: string;
  workerUserId: string;
  springWorkerUserId: string;
};

export type TeamMessageView = {
  id: string;
  sender: string;
  body: string;
  kind: string | null;
  taskId: string | null;
  mentions: string[];
  ts: number;
  msgtype: string;
  url: string | null;
  mimetype: string | null;
  size: number;
  replyTo: string | null;
  replyFallback: { sender: string; text: string } | null;
  chart?: TeamChartReference;
};

export type TeamProgressStep = { id: string; name: string; status: string; kind?: string; result?: string };
export type TeamProgressEntry = { id: string; type: "reasoning" | "step"; chunks?: string[]; stepId?: string };

export type TeamProgressView = {
  id: string;
  sender: string;
  ts: number;
  runId: string;
  status: string;
  answerStarted: boolean;
  startedAt?: number;
  processEndedAt?: number;
  steps: TeamProgressStep[];
  entries: TeamProgressEntry[];
};

export type TeamChartReference =
  | { id: string; inline: ChatChart }
  | { id: string; url: string };

export const CHART_EVENT_TYPE = "com.h.agent.chart";

export const PROGRESS_EVENT_TYPE = "com.h.agent.progress";

export const MEDIA_MSGTYPES = ["m.image", "m.file", "m.audio", "m.video"] as const;

export function isMediaMsgtype(msgtype: string): boolean {
  return (MEDIA_MSGTYPES as readonly string[]).includes(msgtype);
}

export function mediaLabel(msgtype: string): string {
  switch (msgtype) {
    case "m.image": return "[图片]";
    case "m.file": return "[文件]";
    case "m.audio": return "[音频]";
    case "m.video": return "[视频]";
    default: return "[媒体]";
  }
}

export function mediaMsgtypeForMime(mime: string): string {
  if (mime.startsWith("image/")) return "m.image";
  if (mime.startsWith("video/")) return "m.video";
  if (mime.startsWith("audio/")) return "m.audio";
  return "m.file";
}

type RawEvent = {
  event_id?: string;
  sender?: string;
  origin_server_ts?: number;
  type?: string;
  content?: Record<string, unknown>;
};

export function toMessageView(event: RawEvent): TeamMessageView | null {
  if (!event.event_id || !event.sender) return null;
  const content = event.content ?? {};
  if (event.type === CHART_EVENT_TYPE) {
    if (typeof content.chartId !== "string" || typeof content.title !== "string") return null;
    const parsed = chatChartSchema.safeParse(content.chart);
    const chart = parsed.success && parsed.data.id === content.chartId
      ? { id: content.chartId, inline: parsed.data }
      : typeof content.chartUrl === "string" && /^mxc:\/\/[^/]+\/[^/]+$/.test(content.chartUrl)
        ? { id: content.chartId, url: content.chartUrl }
        : null;
    if (!chart) return null;
    return {
      id: event.event_id, sender: event.sender, ts: event.origin_server_ts ?? 0,
      body: content.title, msgtype: CHART_EVENT_TYPE, chart,
      kind: null, taskId: null, mentions: [], url: null, mimetype: null, size: 0,
      replyTo: null, replyFallback: null,
    };
  }
  if (event.type !== "m.room.message") return null;
  const msgtype = typeof content.msgtype === "string" ? content.msgtype : "";
  const media = isMediaMsgtype(msgtype);
  if (msgtype !== "m.text" && !media) return null;
  const url = media && typeof content.url === "string" ? content.url : null;
  if (media && !url) return null;
  let body = typeof content.body === "string" ? content.body : "";
  if (!body) {
    if (!media) return null;
    body = url && url.includes("/") ? url.slice(url.lastIndexOf("/") + 1) : "附件";
  }
  const mentionBlock = (content["m.mentions"] ?? {}) as { user_ids?: unknown };
  const mentions = Array.isArray(mentionBlock.user_ids)
    ? mentionBlock.user_ids.filter((id): id is string => typeof id === "string")
    : [];
  const team = (content["com.h.team"] ?? {}) as { kind?: unknown; taskId?: unknown };
  const info = (content.info ?? {}) as { mimetype?: unknown; size?: unknown };
  const relates = (content["m.relates_to"] ?? {}) as { "m.in_reply_to"?: { event_id?: unknown } };
  const replyTo = typeof relates["m.in_reply_to"]?.event_id === "string" ? relates["m.in_reply_to"].event_id : null;
  let replyFallback: { sender: string; text: string } | null = null;
  if (replyTo && !media) {
    const parsed = stripReplyFallback(body);
    body = parsed.body;
    replyFallback = parsed.fallback;
    if (!body) return null;
  }
  return {
    id: event.event_id,
    sender: event.sender,
    body,
    kind: typeof team.kind === "string" ? team.kind : null,
    taskId: typeof team.taskId === "string" ? team.taskId : null,
    mentions,
    ts: event.origin_server_ts ?? 0,
    msgtype,
    url,
    mimetype: typeof info.mimetype === "string" ? info.mimetype : null,
    size: typeof info.size === "number" ? info.size : 0,
    replyTo,
    replyFallback,
  };
}

/** Matrix 回复的 body 以 "> <@sender> 原文" 降级行开头；展示时剥离，保留给 agent 阅读。 */
export function stripReplyFallback(body: string): { body: string; fallback: { sender: string; text: string } | null } {
  if (!body.startsWith("> ")) return { body, fallback: null };
  const lines = body.split("\n");
  let end = 0;
  while (end < lines.length && lines[end].startsWith(">")) end++;
  const quoted = lines.slice(0, end);
  const first = quoted[0] ?? "";
  const match = /^> <(@[^>]+)> ?(.*)$/.exec(first);
  const fallback = match
    ? { sender: match[1], text: [match[2], ...quoted.slice(1).map((line) => line.replace(/^> ?/, ""))].join("\n").trim() }
    : null;
  let rest = lines.slice(end).join("\n");
  if (rest.startsWith("\n")) rest = rest.slice(1);
  return { body: rest, fallback };
}

export function toProgressView(event: RawEvent): TeamProgressView | null {
  if (event.type !== PROGRESS_EVENT_TYPE || !event.event_id || !event.sender) return null;
  const content = event.content ?? {};
  if (typeof content.runId !== "string") return null;
  const steps = Array.isArray(content.steps) ? (content.steps as TeamProgressStep[]).filter((step) => step && typeof step.id === "string") : [];
  const entries = Array.isArray(content.entries) ? (content.entries as TeamProgressEntry[]).filter((entry) => entry && typeof entry.id === "string") : [];
  return {
    id: event.event_id,
    sender: event.sender,
    ts: event.origin_server_ts ?? 0,
    runId: content.runId,
    status: typeof content.status === "string" ? content.status : "RUNNING",
    answerStarted: content.answerStarted === true,
    startedAt: typeof content.startedAt === "number" ? content.startedAt : undefined,
    processEndedAt: typeof content.processEndedAt === "number" ? content.processEndedAt : undefined,
    steps,
    entries,
  };
}

export function buildMediaContent(msgtype: string, url: string, body: string, mimetype: string, size: number) {
  return {
    msgtype,
    body,
    url,
    info: { mimetype, size },
  };
}

export function mediaSummary(view: TeamMessageView): string {
  return `${mediaLabel(view.msgtype)} ${view.body}`;
}

export function buildTextContent(body: string, mentionIds: string[]) {
  const content: Record<string, unknown> = { msgtype: "m.text", body };
  if (mentionIds.length > 0) {
    content["m.mentions"] = { user_ids: mentionIds };
  }
  return content;
}

export type ReplyTarget = { eventId: string; sender: string; body: string; msgtype: string };

export function buildReplyContent(body: string, mentionIds: string[], reply: ReplyTarget) {
  const quoted = isMediaMsgtype(reply.msgtype) ? `${mediaLabel(reply.msgtype)} ${reply.body}` : reply.body;
  const firstLine = quoted.split("\n")[0].slice(0, 120);
  const content = buildTextContent(`> <${reply.sender}> ${firstLine}\n\n${body}`, mentionIds);
  content["m.relates_to"] = { "m.in_reply_to": { event_id: reply.eventId } };
  return content;
}

const MXC_PATTERN = /mxc:\/\/[A-Za-z0-9._~-]+\/[A-Za-z0-9._~-]+/g;

/** 文本里裸写的 mxc:// 地址转成 markdown 链接，供下载/预览渲染；已在链接或代码里的不动。 */
export function linkifyMxc(body: string): string {
  return body.replace(MXC_PATTERN, (match, offset: number) => {
    const before = body.slice(Math.max(0, offset - 2), offset);
    if (before.endsWith("](") || before.endsWith("`") || before.endsWith("[")) return match;
    const name = match.slice(match.lastIndexOf("/") + 1);
    return `[${name}](${match})`;
  });
}

export function senderLabel(sender: string, selfUserId: string, config: TeamConfig): string {
  if (sender === selfUserId) return "自己";
  if (sender === config.managerUserId) return "Manager";
  if (sender === config.workerUserId) return "Worker";
  if (sender === config.springWorkerUserId) return "SpringWorker";
  return sender.replace(/^@/, "").split(":")[0];
}

export function kindLabel(kind: string | null): string | null {
  switch (kind) {
    case "delegate": return "委派";
    case "report": return "回报";
    case "question": return "提问";
    default: return null;
  }
}

export type TeamMember = { label: string; userId: string; description: string };

export function teamMembers(config: TeamConfig): TeamMember[] {
  return [
    { label: "Manager", userId: config.managerUserId, description: "协调" },
    { label: "Worker", userId: config.workerUserId, description: "执行 · LangChain4j" },
    { label: "SpringWorker", userId: config.springWorkerUserId, description: "执行 · Spring AI" },
  ];
}

export function mentionTrigger(text: string, caret: number): { start: number; query: string } | null {
  const match = /@([A-Za-z0-9_]*)$/.exec(text.slice(0, caret));
  return match ? { start: caret - match[0].length, query: match[1] } : null;
}

export function mentionIdsFor(text: string, config: TeamConfig): string[] {
  return [
    ...(/@manager\b/i.test(text) ? [config.managerUserId] : []),
    ...(/@worker\b/i.test(text) ? [config.workerUserId] : []),
    ...(/@springworker\b/i.test(text) ? [config.springWorkerUserId] : []),
  ];
}
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
};

type RawEvent = {
  event_id?: string;
  sender?: string;
  origin_server_ts?: number;
  type?: string;
  content?: Record<string, unknown>;
};

export function toMessageView(event: RawEvent): TeamMessageView | null {
  if (event.type !== "m.room.message" || !event.event_id || !event.sender) return null;
  const content = event.content ?? {};
  if (content.msgtype !== "m.text" || typeof content.body !== "string" || !content.body) return null;
  const mentionBlock = (content["m.mentions"] ?? {}) as { user_ids?: unknown };
  const mentions = Array.isArray(mentionBlock.user_ids)
    ? mentionBlock.user_ids.filter((id): id is string => typeof id === "string")
    : [];
  const team = (content["com.h.team"] ?? {}) as { kind?: unknown; taskId?: unknown };
  return {
    id: event.event_id,
    sender: event.sender,
    body: content.body,
    kind: typeof team.kind === "string" ? team.kind : null,
    taskId: typeof team.taskId === "string" ? team.taskId : null,
    mentions,
    ts: event.origin_server_ts ?? 0,
  };
}

export function buildTextContent(body: string, mentionIds: string[]) {
  const content: Record<string, unknown> = { msgtype: "m.text", body };
  if (mentionIds.length > 0) {
    content["m.mentions"] = { user_ids: mentionIds };
  }
  return content;
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
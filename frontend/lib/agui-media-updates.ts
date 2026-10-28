import type { Message } from "@ag-ui/core";
import type { ChatSessionMessage } from "./chat-sessions";
import { productHistoryMessages } from "./agui-messages.ts";

const pendingStatuses = new Set(["PENDING", "PROCESSING", "RUNNING", "PENDING_SUBMISSION", "IN_PROGRESS", "MATERIALIZING", "RETRY_WAIT"]);

function sameValue(left: unknown, right: unknown): boolean {
  if (left === right) return true;
  if (!left || !right || typeof left !== "object" || typeof right !== "object"
    || Array.isArray(left) !== Array.isArray(right)) return false;
  const first = left as Record<string, unknown>;
  const second = right as Record<string, unknown>;
  const keys = Object.keys(first);
  return keys.length === Object.keys(second).length
    && keys.every((key) => Object.hasOwn(second, key) && sameValue(first[key], second[key]));
}

/** Only incomplete generated media needs a background request. Later snapshots win. */
export function pendingMediaMessageIds(messages: Message[]): string[] {
  const latest = new Map<string, ChatSessionMessage>();
  for (const message of messages) {
    if (message.role !== "activity" || message.activityType !== "h-agent.resource") continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    if (source) latest.set(source.id, source);
  }
  return [...latest.values()].filter((message) => message.role === "assistant"
    && ["IMAGE", "VIDEO"].includes(message.messageType)
    && pendingStatuses.has(message.payload?.status ?? "")
    && !message.resources?.some((resource) => resource.type.toUpperCase() === message.messageType))
    .map((message) => message.id);
}

/** Patch product messages by identity; leave SDK process/tool state and unchanged objects intact. */
export function mergeProductMessageUpdates(messages: Message[], updates: ChatSessionMessage[]): Message[] {
  const projected = productHistoryMessages(updates);
  const replacements = new Map(projected.map((message) => [message.id, message]));
  let changed = false;
  const merged = messages.map((message) => {
    const update = replacements.get(message.id);
    if (!update) return message;
    replacements.delete(message.id);
    const next = message.role === "activity" && update.role === "activity"
      ? { ...message, content: { ...message.content, ...update.content } }
      : { ...message, content: update.content };
    if (sameValue(message, next)) return message;
    changed = true;
    return next as Message;
  });
  if (replacements.size) {
    changed = true;
    merged.push(...projected.filter((message) => replacements.has(message.id)));
  }
  return changed ? merged : messages;
}

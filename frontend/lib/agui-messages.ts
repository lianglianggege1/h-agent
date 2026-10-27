import type { ActivityMessage, Message } from "@ag-ui/core";
import type { ChatMessageResource, ChatSessionMessage } from "./chat-sessions";

/** The same history projection used on reconnect, also needed when prepending older REST pages. */
export function productHistoryMessages(history: ChatSessionMessage[], following: ChatSessionMessage[] = []): Message[] {
  const context = [...history, ...following];
  const hasText = (message: ChatSessionMessage) => message.messageType !== "REASONING" && message.role !== "system";
  const cardAnchor = (index: number) => {
    let next = index + 1;
    if (history[index].messageType === "REASONING") {
      while (context[next]?.messageType === "IMAGE") next++;
      if (context[next]?.messageType !== "AI" && context[next]?.role !== "blocked") next = index + 1;
    }
    const nextMessage = context.slice(next).find(hasText);
    if (nextMessage) return { anchorMessageId: nextMessage.id, anchorPlacement: "BEFORE" };
    const previous = history.slice(0, index).findLast(hasText);
    return previous ? { anchorMessageId: previous.id, anchorPlacement: "AFTER" } : {};
  };
  return history.flatMap((message, index): Message[] => {
    if (!hasText(message)) return [{
      id: (message.messageType === "REASONING" ? "reasoning-" : "assignment-") + message.id,
      role: "activity", activityType: message.messageType === "REASONING" ? "h-agent.reasoning" : "h-agent.assignment",
      content: { text: message.content, ...cardAnchor(index),
        turnAnchorMessageId: history.slice(0, index).findLast((item) => item.role === "user")?.id },
    }];
    const text: Message = { id: message.id, role: message.role === "user" ? "user" : "assistant", content: message.content };
    return message.resources?.length || message.payload ? [text, {
      id: "resource-" + message.id, role: "activity", activityType: "h-agent.resource",
      content: { message, anchorMessageId: message.id, anchorPlacement: "AFTER" },
    }] : [text];
  });
}

/** References are part of the sent user message; generated outputs keep their resource cards. */
function attachUserReferences(messages: Message[]): Message[] {
  const users = new Set(messages.filter((message) => message.role === "user").map((message) => message.id));
  const references = new Map<string, Map<string, ChatMessageResource>>();
  const cards = new Map<string, ActivityMessage | null>();
  for (const message of messages) {
    if (message.role !== "activity" || message.activityType !== "h-agent.resource") continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    if (source?.role !== "user" || !users.has(source.id)) continue;
    const images = source.resources?.filter((resource) => resource.role === "REFERENCE" && /^(image|video|audio)\//.test(resource.mimeType)) ?? [];
    if (!images.length) continue;
    const selected = references.get(source.id) ?? new Map<string, ChatMessageResource>();
    images.forEach((image) => selected.set(image.id, image));
    references.set(source.id, selected);
    const remaining = source.resources?.filter((resource) => !selected.has(resource.id)) ?? [];
    cards.set(message.id, remaining.length || source.payload ? {
      ...message, content: { ...message.content, message: { ...source, resources: remaining } },
    } : null);
  }
  return messages.flatMap((message): Message[] => {
    if (cards.has(message.id)) return cards.get(message.id) ? [cards.get(message.id)!] : [];
    if (message.role !== "user" || !references.has(message.id)) return [message];
    const text = typeof message.content === "string" ? [{ type: "text" as const, text: message.content }] : message.content;
    return [{ ...message, content: [...text, ...[...references.get(message.id)!.values()].map((resource) => ({
      type: (resource.mimeType.startsWith("video/") ? "video" : resource.mimeType.startsWith("audio/") ? "audio" : "image") as "image" | "video" | "audio", source: { type: "url" as const, value: resource.viewUrl, mimeType: resource.mimeType },
      metadata: { hAgentReference: true, resourceId: resource.id, filename: resource.fileName },
    }))] }];
  });
}

type ProcessEntry = { id: string; type: "reasoning" | "step"; chunks?: string[]; stepId?: string };
type ProcessStep = { id: string; name: string; status: string; kind?: string; result?: string };

/** Keep the SDK's wire state intact; only the product view combines a turn's process. */
function unifyProcessMessages(messages: Message[]): Message[] {
  const turns = new Map<string, string>();
  const answered = new Set<string>();
  let turn: string | undefined;
  for (const message of messages) {
    if (message.role === "user") turn = message.id;
    if (message.role !== "activity" && turn) {
      turns.set(message.id, turn);
      if (message.role === "assistant" && typeof message.content === "string" && message.content.trim()) answered.add(turn);
    }
  }
  const owner = (message: ActivityMessage) => {
    const anchor = message.content.turnAnchorMessageId ?? message.content.anchorMessageId;
    return typeof anchor === "string" ? turns.get(anchor) ?? anchor : message.id;
  };
  const groups = new Map<string, ActivityMessage[]>();
  for (const message of messages) {
    if (message.role !== "activity" || message.activityType !== "h-agent.progress") continue;
    const key = owner(message);
    groups.set(key, [...groups.get(key) ?? [], message]);
  }
  const replacements = new Map<string, ActivityMessage>();
  const hidden = new Set<string>();
  const toolIds = new Set<string>();
  for (const [key, sources] of groups) {
    // Snapshot replay order is normally chronological; timestamps also cover a late reconnect.
    const ordered = [...sources].sort((a, b) => Number(a.content.startedAt ?? 0) - Number(b.content.startedAt ?? 0));
    const first = ordered[0];
    const last = ordered.at(-1)!;
    const steps = new Map<string, ProcessStep>();
    const entries: ProcessEntry[] = [];
    for (const source of ordered) {
      const sourceSteps = (source.content.steps ?? []) as ProcessStep[];
      for (const step of sourceSteps) {
        steps.set(step.id, step);
        if (step.id.startsWith("tool-")) toolIds.add(step.id.slice(5));
      }
      const sourceEntries = source.content.entries as ProcessEntry[] | undefined;
      const timeline: ProcessEntry[] = sourceEntries?.length ? [...sourceEntries] : [
        ...(source.content.reasoning ? [{ id: "legacy", type: "reasoning" as const, chunks: [String(source.content.reasoning)] }] : []),
        ...sourceSteps.map((step) => ({ id: step.id, type: "step" as const, stepId: step.id })),
      ];
      entries.push(...timeline.map((entry) => ({ ...entry, id: `${source.id}/${entry.id}` })));
      if (source.id !== first.id) hidden.add(source.id);
    }
    const legacy = messages.filter((message): message is ActivityMessage => message.role === "activity"
      && message.activityType === "h-agent.reasoning" && owner(message) === key);
    // Old snapshots can have tools without reasoning. Keep the persisted reasoning as a fallback.
    if (!entries.some((entry) => entry.type === "reasoning") && legacy.length) {
      entries.unshift(...legacy.map((message) => ({ id: message.id, type: "reasoning" as const, chunks: [String(message.content.text ?? "")] })));
    }
    legacy.forEach((message) => hidden.add(message.id));
    replacements.set(first.id, { ...first, content: {
      ...last.content, runId: first.content.runId ?? first.id, startedAt: first.content.startedAt,
      anchorMessageId: first.content.anchorMessageId, anchorPlacement: first.content.anchorPlacement,
      answerStarted: ordered.some((source) => source.content.answerStarted === true)
        || (ordered.every((source) => typeof source.content.answerStarted !== "boolean") && answered.has(key)),
      reasoning: "", entries, steps: [...steps.values()],
    } });
  }
  return messages.flatMap((message): Message[] => {
    if (hidden.has(message.id)) return [];
    if (message.role === "tool" && toolIds.has(message.toolCallId)) return [];
    if (message.role === "assistant" && message.toolCalls?.some((call) => toolIds.has(call.id))) {
      const toolCalls = message.toolCalls.filter((call) => !toolIds.has(call.id));
      if (!message.content && !toolCalls.length) return [];
      return [{ ...message, toolCalls }];
    }
    return [replacements.get(message.id) ?? message];
  });
}

/** SDK keeps activity identity/state; this product transform places cards beside their owning turn. */
export function orderProductMessages(messages: Message[]): Message[] {
  messages = attachUserReferences(unifyProcessMessages(messages));
  const indexes = new Map(messages.map((message, index) => [message.id, index]));
  return messages.map((message, index) => {
    if (message.role !== "activity") return { message, position: index };
    const content = message.content;
    const proposal = content.proposal as { anchorMessageId?: string; anchorPlacement?: string } | undefined;
    const anchor = typeof content.anchorMessageId === "string" ? content.anchorMessageId : proposal?.anchorMessageId;
    if (!anchor) return { message, position: index };
    const owner = indexes.get(anchor);
    // Keep unloaded cards in SDK state so loading an older page can reveal them at the correct turn.
    if (owner === undefined) return null;
    const placement = content.anchorPlacement ?? proposal?.anchorPlacement ?? "AFTER";
    return { message, position: owner + (placement === "BEFORE" ? -0.25 : 0.25) };
  }).filter((item) => item !== null).sort((left, right) => left.position - right.position)
    .map((item) => item.message);
}

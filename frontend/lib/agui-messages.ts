import type { Message } from "@ag-ui/core";
import type { ChatSessionMessage } from "./chat-sessions";

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
      content: { text: message.content, ...cardAnchor(index) },
    }];
    const text: Message = { id: message.id, role: message.role === "user" ? "user" : "assistant", content: message.content };
    return message.resources?.length || message.payload ? [text, {
      id: "resource-" + message.id, role: "activity", activityType: "h-agent.resource",
      content: { message, anchorMessageId: message.id, anchorPlacement: "AFTER" },
    }] : [text];
  });
}

/** SDK keeps activity identity/state; this product transform places cards beside their owning turn. */
export function orderProductMessages(messages: Message[]): Message[] {
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

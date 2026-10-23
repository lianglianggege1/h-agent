import type { Message } from "@ag-ui/core";

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

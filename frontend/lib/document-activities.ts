import type { Message } from "@ag-ui/core";
import type { DocumentTask } from "./documents";
import type { ChatSessionMessage } from "./chat-sessions";

/** Stable task identities update a card in place, including after reconnect or history pagination. */
export function mergeDocumentTaskActivities(
  messages: Message[],
  tasks: DocumentTask[],
): Message[] {
  const delivered = new Set(messages.flatMap((message) =>
    message.role === "activity" && message.activityType === "h-agent.resource"
      ? ((message.content.message as { resources?: { id: string }[] } | undefined)
          ?.resources?.map((resource) => resource.id) ?? [])
      : [],
  ));
  const completed = tasks.filter((task) => task.kind === "GENERATE" &&
    task.status === "COMPLETED" && task.outputResourceId && delivered.has(task.outputResourceId));
  const turns = new Map<string, string>();
  let turn = "";
  for (const message of messages) {
    if (message.role === "user") turn = message.id;
    if (message.role !== "activity") turns.set(message.id, turn);
  }
  const redundant = new Set<string>();
  for (const message of messages) {
    if (message.role !== "activity" || message.activityType !== "h-agent.resource") continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    if (source?.role !== "assistant" || source.messageType !== "FILE" || source.resources?.length !== 1) continue;
    const resource = source.resources[0];
    if (completed.some((task) => task.anchorMessageId === turns.get(source.id) &&
      task.fileName === resource.fileName && task.outputResourceId !== resource.id)) {
      redundant.add(message.id);
      redundant.add(source.id);
    }
  }
  const visible = tasks.filter(
    (task) => task.status !== "SUPERSEDED" && task.anchorMessageId &&
      !(task.kind === "GENERATE" && task.status === "COMPLETED" &&
        task.outputResourceId && delivered.has(task.outputResourceId)),
  );
  const ids = new Set(visible.map((task) => `document-${task.taskId}`));
  let changed = false;
  const next = messages.filter((message) => {
    const keep =
      !redundant.has(message.id) && (message.role !== "activity" ||
      message.activityType !== "h-agent.document" || ids.has(message.id));
    if (!keep) changed = true;
    return keep;
  });
  for (const task of visible) {
    const id = `document-${task.taskId}`;
    const index = next.findIndex((message) => message.id === id);
    const existing = index < 0 ? undefined : next[index];
    if (
      existing?.role === "activity" &&
      JSON.stringify(existing.content.task) === JSON.stringify(task)
    )
      continue;
    const activity: Message = {
      id,
      role: "activity",
      activityType: "h-agent.document",
      content: {
        task,
        anchorMessageId: task.anchorMessageId,
        anchorPlacement: "AFTER",
      },
    };
    if (index < 0) next.push(activity);
    else next[index] = activity;
    changed = true;
  }
  return changed ? next : messages;
}

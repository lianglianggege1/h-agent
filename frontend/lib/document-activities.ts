import type { Message } from "@ag-ui/core";
import type { DocumentTask } from "./documents";

/** Stable task identities update a card in place, including after reconnect or history pagination. */
export function mergeDocumentTaskActivities(
  messages: Message[],
  tasks: DocumentTask[],
): Message[] {
  const visible = tasks.filter(
    (task) => task.status !== "SUPERSEDED" && task.anchorMessageId,
  );
  const ids = new Set(visible.map((task) => `document-${task.taskId}`));
  let changed = false;
  const next = messages.filter((message) => {
    const keep =
      message.role !== "activity" ||
      message.activityType !== "h-agent.document" ||
      ids.has(message.id);
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

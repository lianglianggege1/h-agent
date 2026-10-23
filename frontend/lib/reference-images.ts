import type { Message } from "@ag-ui/core";
import type { ChatMessageResource, ChatSessionMessage } from "./chat-sessions";
import type { UploadedResource } from "./resource-upload";

export function historyReferenceImages(messages: Message[]): ChatMessageResource[] {
  const images = new Map<string, ChatMessageResource>();
  for (const message of messages) {
    if (message.role !== "activity" || message.activityType !== "h-agent.resource") continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    if (source?.messageType !== "IMAGE") continue;
    for (const resource of source.resources ?? []) {
      if (resource.type === "IMAGE" && resource.mimeType.startsWith("image/")) images.set(resource.id, resource);
    }
  }
  return [...images.values()];
}

export function selectHistoryReference(current: UploadedResource[], resource: ChatMessageResource): UploadedResource[] {
  return [...current.filter((item) => item.resourceId !== resource.id), {
    resourceId: resource.id, type: resource.type, role: "REFERENCE", source: "HISTORY",
    viewUrl: resource.viewUrl, downloadUrl: resource.downloadUrl, fileName: resource.fileName,
    mimeType: resource.mimeType, fileSize: resource.fileSize ?? 0,
  }];
}

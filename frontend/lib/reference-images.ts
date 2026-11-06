import type { Message } from "@ag-ui/core";
import type { ChatMessageResource, ChatSessionMessage } from "./chat-sessions";
import type { UploadedResource } from "./resource-upload";

export function historyReferenceImages(
  messages: Message[],
): ChatMessageResource[] {
  const images = new Map<string, ChatMessageResource>();
  for (const message of messages) {
    if (
      message.role !== "activity" ||
      message.activityType !== "h-agent.resource"
    )
      continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    if (source?.messageType !== "IMAGE") continue;
    for (const resource of source.resources ?? []) {
      if (resource.type === "IMAGE" && resource.mimeType.startsWith("image/"))
        images.set(resource.id, resource);
    }
  }
  return [...images.values()];
}

/** 媒体沿用多模态引用；文档保留附件身份，交给文档工具读取。 */
export function selectHistoryReference(
  current: UploadedResource[],
  resource: ChatMessageResource,
): UploadedResource[] {
  return [
    ...current.filter((item) => item.resourceId !== resource.id),
    {
      resourceId: resource.id,
      type: resource.type,
      role: /^(image|video|audio)\//.test(resource.mimeType)
        ? "REFERENCE"
        : "ATTACHMENT",
      source: "HISTORY",
      viewUrl: resource.viewUrl,
      downloadUrl: resource.downloadUrl,
      fileName: resource.fileName,
      mimeType: resource.mimeType,
      fileSize: resource.fileSize,
    },
  ];
}

/** 当前会话已有的全部文件，包含用户附件与生成结果，重复消息引用按资源 ID 去重。 */
export function historyReferenceResources(
  messages: Message[],
): ChatMessageResource[] {
  const resources = new Map<string, ChatMessageResource>();
  for (const message of messages) {
    if (
      message.role !== "activity" ||
      message.activityType !== "h-agent.resource"
    )
      continue;
    const source = message.content.message as ChatSessionMessage | undefined;
    for (const resource of source?.resources ?? []) {
      resources.set(resource.id, resource);
    }
  }
  return [...resources.values()];
}

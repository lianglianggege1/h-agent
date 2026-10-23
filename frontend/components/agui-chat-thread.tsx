"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { CopilotChat, useAgent, useCopilotKit, useRenderTool, type CopilotChatViewProps } from "@copilotkit/react-core/v2";
import type { Message } from "@ag-ui/core";
import { apiFetch } from "@/lib/http";
import { getChatSessionMessages, type ChatSessionMessage } from "@/lib/chat-sessions";
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";
import { ToolResult, ResourceReferenceContext } from "@/components/agui-cards";
import { orderProductMessages } from "@/lib/agui-messages";

type ThreadActions = { submit: (text: string) => void; addFile: () => void; disabled: boolean };
const Actions = createContext<ThreadActions | null>(null);

function ThreadView(props: CopilotChatViewProps) {
  const actions = useContext(Actions);
  return <CopilotChat.View {...props}
    messageView={{ transformMessages: orderProductMessages }}
    onSubmitMessage={(value) => {
      if (!actions || actions.disabled) return;
      actions.submit(value);
      props.onInputChange?.("");
    }}
    input={{ onAddFile: actions?.addFile, textArea: { placeholder: "输入消息…", maxLength: 4000, disabled: actions?.disabled } }}
  />;
}

function productMessage(message: ChatSessionMessage): Message {
  return { id: message.id, role: message.role === "user" ? "user" : "assistant", content: message.content };
}

export function AguiChatThread({ threadId, onChanged }: { threadId: string; onChanged?: () => void }) {
  const localAgentId = "thread-" + threadId;
  const { agent, isReady } = useAgent({ agentId: localAgentId, runtimeAgentId: "default", threadId, throttleMs: 40 });
  const { copilotkit } = useCopilotKit();
  const [attachments, setAttachments] = useState<UploadedResource[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState("");
  const [beforeSeq, setBeforeSeq] = useState<number | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const onChangedRef = useRef(onChanged);
  useEffect(() => { onChangedRef.current = onChanged; }, [onChanged]);

  useRenderTool({ name: "*", render: ToolResult }, []);

  useEffect(() => {
    if (!isReady) return;
    let disposed = false;
    const subscription = agent.subscribe({
      onRunErrorEvent: ({ event }) => { if (!disposed) setError(event.message); },
      onRunFinalized: () => { if (!disposed) onChangedRef.current?.(); },
      onActivitySnapshotEvent: ({ event }) => {
        if (event.activityType === "h-agent.progress" && !disposed) setWaiting(event.content.status === "WAITING_APPROVAL");
      },
    });
    void getChatSessionMessages(threadId, 100).then((page) => {
      if (!disposed) { setBeforeSeq(page.nextBeforeSeq); setHasOlder(page.hasMore); }
    }).catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : "历史加载失败"); });
    return () => { disposed = true; subscription.unsubscribe(); };
  }, [agent, isReady, threadId]);

  async function submit(text: string) {
    if (!isReady || agent.isRunning || uploading || waiting || !text.trim()) return;
    setError("");
    const resources = attachments.map(({ resourceId, role, source }) => ({ resourceId, role, source }));
    agent.addMessage({ id: crypto.randomUUID(), role: "user", content: text.trim() });
    setAttachments([]);
    try { await copilotkit.runAgent({ agent, forwardedProps: { resources } }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "发送失败，请重试"); }
  }

  async function stop() {
    setError("");
    try {
      await apiFetch("/api/chat/agui/threads/" + encodeURIComponent(threadId) + "/stop", { method: "POST" });
      // The explicit backend stop is authoritative. Reconnecting only observes the stopped snapshot.
      await agent.detachActiveRun();
      setWaiting(false);
      await copilotkit.connectAgent({ agent });
      onChangedRef.current?.();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "停止失败，请重试"); }
  }

  async function loadOlder() {
    if (!beforeSeq || loadingOlder || agent.isRunning) return;
    setLoadingOlder(true);
    try {
      const page = await getChatSessionMessages(threadId, 50, beforeSeq);
      const ids = new Set(agent.messages.map((message) => message.id));
      agent.setMessages([...page.messages.filter((message) => message.messageType !== "REASONING" && !ids.has(message.id)).map(productMessage), ...agent.messages]);
      setBeforeSeq(page.nextBeforeSeq); setHasOlder(page.hasMore);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "历史加载失败"); }
    finally { setLoadingOlder(false); }
  }

  const refresh = useCallback(async () => {
    if (!isReady || agent.isRunning) return;
    try { setError(""); await copilotkit.connectAgent({ agent }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "恢复连接失败"); }
  }, [agent, copilotkit, isReady]);

  // Async media and other external deliveries still use the product database. Hydrate through
  // the same SDK connection when idle, instead of maintaining a second SSE implementation.
  useEffect(() => {
    const timer = window.setInterval(() => {
      const pendingMedia = agent.messages.some((message) => message.role === "activity"
        && message.activityType === "h-agent.resource"
        && ["PENDING", "PROCESSING", "RUNNING"].includes(String((message.content.message as ChatSessionMessage | undefined)?.payload?.status)));
      if (pendingMedia) void refresh();
    }, 10_000);
    const visible = () => { if (document.visibilityState === "visible") void refresh(); };
    document.addEventListener("visibilitychange", visible);
    return () => { clearInterval(timer); document.removeEventListener("visibilitychange", visible); };
  }, [agent, refresh]);

  return <section className="agui-thread flex min-h-0 flex-1 flex-col" aria-label="对话">
    <div className="flex shrink-0 items-center justify-between px-4 py-2 text-xs text-stone-500">
      {hasOlder ? <button className="underline disabled:opacity-40" disabled={loadingOlder || agent.isRunning} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "加载更早消息"}</button> : <span />}
      <button className="underline" onClick={() => void refresh()}>恢复连接</button>
    </div>
    {error ? <div role="alert" className="mx-4 mb-2 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
    {waiting ? <p className="mx-4 mb-2 text-xs text-amber-800">等待批准，请在批准卡片中选择。</p> : null}
    <input ref={fileInput} type="file" multiple className="hidden" onChange={(event) => {
      const files = Array.from(event.target.files ?? []);
      event.target.value = "";
      if (!files.length) return;
      setUploading(true); setError("");
      void Promise.all(files.map((file) => uploadChatResource(file))).then((uploaded) => setAttachments((current) => [...current, ...uploaded]))
        .catch((failure) => setError(failure instanceof Error ? failure.message : "上传失败"))
        .finally(() => setUploading(false));
    }} />
    {attachments.length || uploading ? <div className="flex shrink-0 flex-wrap gap-2 px-4 py-2">
      {attachments.map((item) => <span key={item.resourceId} className="rounded-full border border-stone-200 bg-white px-3 py-1 text-xs">{item.fileName}<button aria-label={"移除 " + item.fileName} className="ml-2" onClick={() => setAttachments((current) => current.filter((resource) => resource.resourceId !== item.resourceId))}>×</button></span>)}
      {uploading ? <span className="text-xs text-stone-500">上传中…</span> : null}
    </div> : null}
    <ResourceReferenceContext.Provider value={(resource) => setAttachments((current) => [
      ...current.filter((item) => item.resourceId !== resource.id && item.role !== "REFERENCE"),
      { resourceId: resource.id, type: resource.type, role: "REFERENCE", source: "HISTORY", viewUrl: resource.viewUrl,
        downloadUrl: resource.downloadUrl, fileName: resource.fileName, mimeType: resource.mimeType, fileSize: resource.fileSize ?? 0 },
    ])}>
    <Actions.Provider value={{ submit: (value) => void submit(value), addFile: () => fileInput.current?.click(), disabled: !isReady || agent.isRunning || uploading || waiting }}>
      {isReady ? <CopilotChat agentId={localAgentId} threadId={threadId} chatView={ThreadView} className="min-h-0 flex-1" throttleMs={40}
        onStop={() => void stop()} inspectorTools={false}
        labels={{ chatInputPlaceholder: "输入消息…" }}
        onError={(event) => { if ("error" in event) setError(event.error.message); }} />
        : <div className="grid flex-1 place-items-center text-sm text-stone-500">加载对话…</div>}
    </Actions.Provider>
    </ResourceReferenceContext.Provider>
  </section>;
}

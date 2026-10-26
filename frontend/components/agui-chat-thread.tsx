"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { CopilotChat, CopilotChatInput, useAgent, useCopilotKit, useRenderTool, type CopilotChatViewProps, type CopilotChatInputProps } from "@copilotkit/react-core/v2";
import { apiFetch } from "@/lib/http";
import { getChatSessionMessages, type ChatSessionMessage } from "@/lib/chat-sessions";
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";
import { ToolResult, ResourceReferenceContext } from "@/components/agui-cards";
import { orderProductMessages, productHistoryMessages } from "@/lib/agui-messages";
import styles from "./agui-chat-thread.module.css";
import { ChatAttachmentPicker } from "./chat-attachment-picker";
import { historyReferenceImages, selectHistoryReference } from "@/lib/reference-images";
import { OrderReferenceContext } from "./order/order-card";
import { OrderPicker } from "./order/order-picker";
import type { OrderReference } from "@/lib/orders";

type ThreadActions = {
  submit: (text: string) => void;
  addFile: () => void;
  disabled: boolean;
  attachments: UploadedResource[];
  uploading: boolean;
  removeFile: (id: string) => void;
  addOrder?: () => void;
  orderReference: OrderReference | null;
  removeOrder: () => void;
};
const Actions = createContext<ThreadActions | null>(null);

function AttachmentMenuButton() {
  const actions = useContext(Actions);
  if (actions?.addOrder) return <details className="relative">
    <summary aria-label="添加附件" className="grid h-9 w-9 cursor-pointer list-none place-items-center rounded-full text-2xl text-stone-600 hover:bg-stone-100 [&::-webkit-details-marker]:hidden">＋</summary>
    <div className="absolute bottom-11 left-0 z-30 w-48 space-y-1 rounded-xl border border-stone-200 bg-white p-1.5 text-sm shadow-lg">
      <button type="button" className="w-full rounded-lg px-3 py-2.5 text-left hover:bg-stone-100" disabled={actions.disabled} onClick={(event) => { event.currentTarget.closest("details")?.removeAttribute("open"); actions.addOrder?.(); }}>选择订单或工单</button>
      <button type="button" className="w-full rounded-lg px-3 py-2.5 text-left hover:bg-stone-100" disabled={actions.disabled} onClick={(event) => { event.currentTarget.closest("details")?.removeAttribute("open"); actions.addFile(); }}>图片或文件</button>
    </div>
  </details>;
  return <button type="button" aria-label="添加附件" onClick={actions?.addFile} disabled={actions?.disabled}
    className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-2xl text-stone-600 hover:bg-stone-100 disabled:opacity-40">＋</button>;
}

function ThreadInput(props: CopilotChatInputProps) {
  const actions = useContext(Actions);
  return <>
    {actions?.orderReference ? <div className="mx-auto mb-2 w-full max-w-[1000px] px-4" aria-label="待发送引用">
      <div className="flex items-center gap-3 rounded-xl border border-stone-200 bg-white px-3 py-2">
        <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{actions.orderReference.ticketId ? "工单" : "订单"}</span>
        <div className="min-w-0 flex-1"><p className="truncate text-sm">{actions.orderReference.title}</p><p className="truncate text-xs text-stone-500">{actions.orderReference.ticketId ?? actions.orderReference.id}</p></div>
        <button type="button" className="grid h-7 w-7 shrink-0 place-items-center rounded-full text-stone-500 hover:bg-stone-100" aria-label="移除引用" onClick={actions.removeOrder}>×</button>
      </div>
    </div> : null}
    {actions && (actions.attachments.length > 0 || actions.uploading) ? <div className="pointer-events-auto mx-auto mb-2 flex w-full max-w-[1000px] flex-wrap gap-2 px-4" aria-label="待发送附件">
      {actions.attachments.map((item) => <span key={item.resourceId} className="inline-flex items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs">
        {item.mimeType.startsWith("image/") ? <picture><img src={item.viewUrl} alt="" className="h-10 w-10 rounded-lg object-cover" /></picture> : null}
        <span>{item.role === "REFERENCE" ? "参考图 · " : ""}{item.fileName}</span>
        <button aria-label={"移除 " + item.fileName} onClick={() => actions.removeFile(item.resourceId)}>×</button>
      </span>)}
      {actions.uploading ? <span className="text-xs text-stone-500">上传中…</span> : null}
    </div> : null}
    <CopilotChatInput {...props} addMenuButton={AttachmentMenuButton} onAddFile={actions?.addFile}
      textArea={{ placeholder: "输入消息…", maxLength: 4000, disabled: actions?.disabled }} />
  </>;
}

const ProductInput = Object.assign(ThreadInput, CopilotChatInput);

function ThreadView(props: CopilotChatViewProps) {
  const actions = useContext(Actions);
  return <CopilotChat.View {...props}
    messageView={{ transformMessages: orderProductMessages }}
    onSubmitMessage={(value) => {
      if (!actions || actions.disabled) return;
      actions.submit(value);
      props.onInputChange?.("");
    }}
    input={ProductInput}
  />;
}

export function AguiChatThread({ threadId, onChanged, orderAssistant = false }: { threadId: string; onChanged?: () => void; orderAssistant?: boolean }) {
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
  const [pickerOpen, setPickerOpen] = useState(false);
  const [orderPickerOpen, setOrderPickerOpen] = useState(false);
  const [orderReference, setOrderReference] = useState<OrderReference | null>(null);
  const threadElement = useRef<HTMLElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const historyMessages = useRef<ChatSessionMessage[]>([]);
  const onChangedRef = useRef(onChanged);
  useEffect(() => { onChangedRef.current = onChanged; }, [onChanged]);
  useEffect(() => {
    if (orderReference) threadElement.current?.querySelector("textarea")?.focus();
  }, [orderReference]);

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
      if (!disposed) { historyMessages.current = page.messages; setBeforeSeq(page.nextBeforeSeq); setHasOlder(page.hasMore); }
    }).catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : "历史加载失败"); });
    return () => { disposed = true; subscription.unsubscribe(); };
  }, [agent, isReady, threadId]);

  async function submit(text: string) {
    if (!isReady || agent.isRunning || uploading || waiting || !text.trim()) return;
    setError("");
    const resources = attachments.map(({ resourceId, role, source }) => ({ resourceId, role, source }));
    agent.addMessage({ id: crypto.randomUUID(), role: "user", content: text.trim() + (orderReference ? `\n\n关联订单：${orderReference.id}${orderReference.ticketId ? `；关联工单：${orderReference.ticketId}` : ""}` : "") });
    setOrderReference(null);
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
      agent.setMessages([...productHistoryMessages(page.messages, historyMessages.current).filter((message) => !ids.has(message.id)), ...agent.messages]);
      historyMessages.current = [...page.messages, ...historyMessages.current];
      setBeforeSeq(page.nextBeforeSeq); setHasOlder(page.hasMore);
    } catch (failure) { setError(failure instanceof Error ? failure.message : "历史加载失败"); }
    finally { setLoadingOlder(false); }
  }

  const refresh = useCallback(async () => {
    if (!isReady || agent.isRunning) return;
    try { setError(""); await copilotkit.connectAgent({ agent }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "恢复连接失败"); }
  }, [agent, copilotkit, isReady]);

  useEffect(() => {
    if (!orderAssistant) return;
    const changed = () => { void refresh(); onChangedRef.current?.(); };
    window.addEventListener("order:changed", changed);
    // Reconnecting replays the thread and temporarily marks the SDK agent as running,
    // which disables the composer. Order cards poll their own state; reconnect only
    // when a command/reference has actually published new chat content.
    return () => { window.removeEventListener("order:changed", changed); };
  }, [orderAssistant, refresh]);

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

  return <section ref={threadElement} className={`${styles.thread} flex min-h-0 flex-1 flex-col`} aria-label="对话">
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
    {pickerOpen ? <ChatAttachmentPicker images={historyReferenceImages(agent.messages)} hasOlder={hasOlder} loadingOlder={loadingOlder}
      onLoadOlder={() => void loadOlder()} onUpload={() => { setPickerOpen(false); fileInput.current?.click(); }}
      onSelect={(resource) => { setAttachments((current) => selectHistoryReference(current, resource)); setPickerOpen(false); }}
      onClose={() => setPickerOpen(false)} /> : null}
    {orderPickerOpen ? <OrderPicker threadId={threadId} onClose={() => setOrderPickerOpen(false)} onReference={setOrderReference} /> : null}
    <OrderReferenceContext.Provider value={setOrderReference}>
    <ResourceReferenceContext.Provider value={(resource) => setAttachments((current) => selectHistoryReference(current, resource))}>
    <Actions.Provider value={{ submit: (value) => void submit(value), addFile: () => setPickerOpen(true),
      disabled: !isReady || agent.isRunning || uploading || waiting, attachments, uploading,
      removeFile: (id) => setAttachments((current) => current.filter((resource) => resource.resourceId !== id)),
      addOrder: orderAssistant ? () => setOrderPickerOpen(true) : undefined, orderReference, removeOrder: () => setOrderReference(null) }}>
      {isReady ? <CopilotChat agentId={localAgentId} threadId={threadId} chatView={ThreadView} className="min-h-0 flex-1" throttleMs={40}
        onStop={() => void stop()} inspectorTools={false}
        labels={{ chatInputPlaceholder: "输入消息…" }}
        onError={(event) => { if ("error" in event) setError(event.error.message); }} />
        : <div className="grid flex-1 place-items-center text-sm text-stone-500">加载对话…</div>}
    </Actions.Provider>
    </ResourceReferenceContext.Provider>
    </OrderReferenceContext.Provider>
  </section>;
}

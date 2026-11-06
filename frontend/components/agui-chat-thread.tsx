"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { CopilotChat, CopilotChatInput, useAgent, useCopilotKit, useRenderTool, type CopilotChatViewProps, type CopilotChatInputProps } from "@copilotkit/react-core/v2";
import { apiFetch } from "@/lib/http";
import { getChatSessionMessage, getChatSessionMessages, type ChatSessionMessage } from "@/lib/chat-sessions";
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";
import { ToolResult, ResourceReferenceContext } from "@/components/agui-cards";
import { ReferencedUserMessage, UserMessageEditContext } from "@/components/agui-user-message";
import { orderProductMessages, productHistoryMessages } from "@/lib/agui-messages";
import { mergeProductMessageUpdates, pendingMediaMessageIds } from "@/lib/agui-media-updates";
import styles from "./agui-chat-thread.module.css";
import { DocumentTaskSync } from "./document-panel";
import { mergeDocumentTaskActivities } from "@/lib/document-activities";
import type { DocumentTask } from "@/lib/documents";
import { ChatAttachmentPicker } from "./chat-attachment-picker";
import { historyReferenceResources, selectHistoryReference } from "@/lib/reference-images";
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
  paused: boolean;
  pause: () => void;
  resume: () => void;
  busy: boolean;
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
    className={styles.addButton}><svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" aria-hidden="true"><path d="M10 4v12M4 10h12" /></svg></button>;
}

function ThreadInput(props: CopilotChatInputProps) {
  const actions = useContext(Actions);
  const continuing = actions?.paused && !props.value?.trim();
  const label = props.isRunning ? "暂停回答" : continuing ? "继续回答" : "发送消息";
  return <>
    {actions?.orderReference ? <div className="mx-auto mb-2 w-full max-w-[1000px] px-4" aria-label="待发送引用">
      <div className="flex items-center gap-3 rounded-xl border border-stone-200 bg-white px-3 py-2">
        <span className="shrink-0 rounded-md bg-stone-100 px-2 py-1 text-xs text-stone-600">{actions.orderReference.ticketId ? "工单" : "订单"}</span>
        <div className="min-w-0 flex-1"><p className="truncate text-sm">{actions.orderReference.title}</p><p className="truncate text-xs text-stone-500">{actions.orderReference.ticketId ?? actions.orderReference.id}</p></div>
        <button type="button" className="grid h-7 w-7 shrink-0 place-items-center rounded-full text-stone-500 hover:bg-stone-100" aria-label="移除引用" onClick={actions.removeOrder}>×</button>
      </div>
    </div> : null}
    {actions && (actions.attachments.length > 0 || actions.uploading) ? <div className="pointer-events-auto mx-auto mb-2 flex w-full max-w-[1000px] flex-wrap gap-2 px-4" aria-label="待发送附件">
      {actions.attachments.map((item) => <span key={item.resourceId} title={item.fileName} className="inline-flex items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs">
        {item.mimeType.startsWith("image/") ? <picture><img src={item.viewUrl} alt="" className="h-10 w-10 rounded-lg object-cover" /></picture> : item.mimeType.startsWith("video/") ? <video src={item.viewUrl} muted preload="metadata" className="h-10 w-10 rounded-lg object-cover" /> : item.mimeType.startsWith("audio/") ? <span aria-hidden="true" className="grid h-10 w-10 place-items-center rounded-lg bg-stone-100">♫</span> : null}
        <span>{item.role === "REFERENCE" ? (item.mimeType.startsWith("video/") ? "引用视频" : item.mimeType.startsWith("audio/") ? "引用音频" : "引用图片") : item.fileName}</span>
        <button aria-label={"移除 " + item.fileName} onClick={() => actions.removeFile(item.resourceId)}>×</button>
      </span>)}
      {actions.uploading ? <span className="text-xs text-stone-500">上传中…</span> : null}
    </div> : null}
    <CopilotChatInput {...props} addMenuButton={AttachmentMenuButton} onAddFile={actions?.addFile}
      sendButton={{ "aria-label": label, title: label, className: styles.primaryButton,
        onMouseDown: (event) => event.preventDefault(),
        ...(props.isRunning ? { onClick: actions?.pause, disabled: actions?.busy }
          : continuing ? { onClick: actions?.resume, disabled: actions?.disabled } : {}),
        children: <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true">
          {actions?.busy ? <circle className={styles.spinner} cx="10" cy="10" r="6" fill="none" stroke="currentColor" strokeWidth="2" strokeDasharray="28 10" />
            : props.isRunning ? <><rect x="5" y="4" width="3" height="12" rx="1" fill="currentColor" /><rect x="12" y="4" width="3" height="12" rx="1" fill="currentColor" /></>
            : continuing ? <path d="M7 4.5a.8.8 0 0 1 1.2-.7l8 5.5a.8.8 0 0 1 0 1.4l-8 5.5a.8.8 0 0 1-1.2-.7Z" fill="currentColor" />
            : <path d="M10 15.5v-11m-4.5 4.5L10 4.5 14.5 9" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />}
        </svg> }}
      textArea={{ placeholder: actions?.paused ? "输入新消息，或继续回答…" : "输入消息…", maxLength: 4000, disabled: actions?.disabled }} />
  </>;
}

const ProductInput = Object.assign(ThreadInput, CopilotChatInput);

function ThreadView(props: CopilotChatViewProps) {
  const actions = useContext(Actions);
  return <CopilotChat.View {...props}
    messageView={{ transformMessages: orderProductMessages, userMessage: ReferencedUserMessage }}
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
  const [busy, setBusy] = useState(false);
  const [pausedRunId, setPausedRunId] = useState<string | null>(null);
  const [retryEdit, setRetryEdit] = useState<{ messageId: string; text: string } | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [orderPickerOpen, setOrderPickerOpen] = useState(false);
  const [orderReference, setOrderReference] = useState<OrderReference | null>(null);
  const threadElement = useRef<HTMLElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const historyMessages = useRef<ChatSessionMessage[]>([]);
  const onChangedRef = useRef(onChanged);
  const runFailed = useRef(false);
  useEffect(() => { onChangedRef.current = onChanged; }, [onChanged]);
  useEffect(() => {
    const dismissMenus = (event: PointerEvent | KeyboardEvent) => {
      const escape = event.type === "keydown" && (event as KeyboardEvent).key === "Escape";
      if (event.type === "keydown" && !escape) return;
      threadElement.current?.querySelectorAll<HTMLDetailsElement>("details[open]").forEach((menu) => {
        if (!escape && menu.contains(event.target as Node)) return;
        const restoreFocus = escape && menu.contains(document.activeElement);
        menu.removeAttribute("open");
        if (restoreFocus) menu.querySelector("summary")?.focus();
      });
    };
    document.addEventListener("pointerdown", dismissMenus);
    document.addEventListener("keydown", dismissMenus);
    return () => {
      document.removeEventListener("pointerdown", dismissMenus);
      document.removeEventListener("keydown", dismissMenus);
    };
  }, []);
  useEffect(() => {
    if (orderReference) threadElement.current?.querySelector("textarea")?.focus();
  }, [orderReference]);

  useRenderTool({ name: "*", render: ToolResult }, []);

  const refreshTurnState = useCallback(async () => {
    const state = await apiFetch<{ pausedRunId?: string | null }>("/api/chat/agui/threads/" + encodeURIComponent(threadId));
    setPausedRunId(state?.pausedRunId ?? null);
  }, [threadId]);

  useEffect(() => {
    if (!isReady) return;
    let disposed = false;
    const subscription = agent.subscribe({
      onRunInitialized: () => { runFailed.current = false; },
      onRunErrorEvent: ({ event }) => { if (!disposed) { runFailed.current = true; setError(event.message); } },
      onRunFinalized: () => { if (!disposed) {
        if (!runFailed.current) setRetryEdit(null);
        onChangedRef.current?.();
        void refreshTurnState().catch(() => {});
      } },
      onActivitySnapshotEvent: ({ event }) => {
        if (event.activityType === "h-agent.progress" && !disposed) setWaiting(event.content.status === "WAITING_APPROVAL");
      },
    });
    void apiFetch<{ pausedRunId?: string | null }>("/api/chat/agui/threads/" + encodeURIComponent(threadId))
      .then((state) => { if (!disposed) setPausedRunId(state?.pausedRunId ?? null); }).catch(() => {});
    void getChatSessionMessages(threadId, 100).then((page) => {
      if (!disposed) { historyMessages.current = page.messages; setBeforeSeq(page.nextBeforeSeq); setHasOlder(page.hasMore); }
    }).catch((failure) => { if (!disposed) setError(failure instanceof Error ? failure.message : "历史加载失败"); });
    return () => { disposed = true; subscription.unsubscribe(); };
  }, [agent, isReady, threadId, refreshTurnState]);

  async function submit(text: string) {
    if (!isReady || agent.isRunning || uploading || waiting || busy || !text.trim()) return;
    setError("");
    setPausedRunId(null);
    setRetryEdit(null);
    const resources = attachments.map(({ resourceId, role, source }) => ({ resourceId, role, source }));
    const message: ChatSessionMessage = {
      id: crypto.randomUUID(), role: "user", messageType: "USER", createdAt: new Date().toISOString(),
      content: text.trim() + (orderReference ? `\n\n关联订单：${orderReference.id}${orderReference.ticketId ? `；关联工单：${orderReference.ticketId}` : ""}` : ""),
      resources: attachments.map(({ resourceId, ...resource }) => ({ ...resource, id: resourceId, width: null, height: null })),
    };
    agent.addMessages(productHistoryMessages([message]));
    setOrderReference(null);
    setAttachments([]);
    try { await copilotkit.runAgent({ agent, forwardedProps: { resources } }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "发送失败，请重试"); }
  }

  async function revise(messageId: string, text: string) {
    if (!isReady || agent.isRunning || uploading || waiting || busy) return;
    setError(""); setPausedRunId(null); setRetryEdit({ messageId, text });
    try { await copilotkit.runAgent({ agent, forwardedProps: { operation: "edit", messageId, text } }); }
    catch (failure) { setError(failure instanceof Error ? failure.message : "重新回答失败，请重试"); }
  }

  async function resume() {
    if (!isReady || agent.isRunning || uploading || waiting || busy || !pausedRunId) return;
    const sourceRunId = pausedRunId;
    setError(""); setPausedRunId(null);
    try { await copilotkit.runAgent({ agent, forwardedProps: { operation: "continue", sourceRunId } }); }
    catch (failure) {
      setError(failure instanceof Error ? failure.message : "继续回答失败，请重试");
      await refreshTurnState().catch(() => {});
    }
  }

  async function pause() {
    if (busy || !agent.isRunning) return;
    setBusy(true); setError("");
    try {
      await apiFetch("/api/chat/agui/threads/" + encodeURIComponent(threadId) + "/pause", { method: "POST" });
      await agent.detachActiveRun();
      await copilotkit.connectAgent({ agent });
      await refreshTurnState();
      onChangedRef.current?.();
    } catch (failure) { setError(failure instanceof Error ? failure.message : "暂停失败，请重试"); }
    finally { setBusy(false); }
  }

  async function stop() {
    setError("");
    try {
      await apiFetch("/api/chat/agui/threads/" + encodeURIComponent(threadId) + "/stop", { method: "POST" });
      // The explicit backend stop is authoritative. Reconnecting only observes the stopped snapshot.
      await agent.detachActiveRun();
      setWaiting(false);
      await copilotkit.connectAgent({ agent });
      setPausedRunId(null);
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
    if (!orderAssistant || !isReady) return;
    let disposed = false;
    let refreshing = false;
    const changed = async () => {
      if (refreshing || agent.isRunning) return;
      refreshing = true;
      try {
        const page = await getChatSessionMessages(threadId, 100);
        if (disposed || agent.isRunning) return;
        const next = mergeProductMessageUpdates(agent.messages, page.messages);
        if (next !== agent.messages) {
          agent.setMessages(next);
          onChangedRef.current?.();
        }
      } catch (failure) {
        if (!disposed) setError(failure instanceof Error ? failure.message : "消息更新失败");
      } finally { refreshing = false; }
    };
    window.addEventListener("order:changed", changed);
    return () => { disposed = true; window.removeEventListener("order:changed", changed); };
  }, [agent, isReady, orderAssistant, threadId]);

  // Background media delivery patches only its reserved message. Reconnecting the SDK
  // starts a run lifecycle, which disables the composer and replays the entire thread.
  useEffect(() => {
    if (!isReady) return;
    let disposed = false;
    let polling = false;
    let timer: number | undefined;
    const needsUpdates = () => !agent.isRunning && document.visibilityState !== "hidden"
      && pendingMediaMessageIds(agent.messages).length > 0;
    const updatePolling = () => {
      if (!disposed && needsUpdates()) {
        if (timer === undefined) timer = window.setInterval(() => void poll(), 10_000);
      } else if (timer !== undefined) {
        window.clearInterval(timer);
        timer = undefined;
      }
    };
    const poll = async () => {
      if (disposed || polling || !needsUpdates()) return;
      polling = true;
      const ids = pendingMediaMessageIds(agent.messages);
      try {
        const results = await Promise.allSettled(ids.map((id) => getChatSessionMessage(threadId, id)));
        if (disposed || agent.isRunning) return;
        const stillPending = new Set(pendingMediaMessageIds(agent.messages));
        const updates = results.flatMap((result) => result.status === "fulfilled" && stillPending.has(result.value.id)
          ? [result.value] : []);
        const next = mergeProductMessageUpdates(agent.messages, updates);
        if (next !== agent.messages) agent.setMessages(next);
      } finally {
        polling = false;
        updatePolling();
      }
    };
    const subscription = agent.subscribe({ onMessagesChanged: updatePolling,
      onRunInitialized: updatePolling, onRunFinalized: updatePolling });
    const visible = () => {
      updatePolling();
      if (needsUpdates()) void poll();
    };
    updatePolling();
    document.addEventListener("visibilitychange", visible);
    return () => {
      disposed = true;
      if (timer !== undefined) window.clearInterval(timer);
      subscription.unsubscribe();
      document.removeEventListener("visibilitychange", visible);
    };
  }, [agent, isReady, threadId]);

  const updateDocuments = useCallback(async (tasks: DocumentTask[]) => {
    if (!tasks.length && !agent.messages.some(message => message.role === "activity" && message.activityType === "h-agent.document")) return;
    if (agent.isRunning) {
      const next = mergeDocumentTaskActivities(agent.messages, tasks);
      if (next !== agent.messages) agent.setMessages(next);
      return;
    }
    const page = await getChatSessionMessages(threadId, 100);
    if (agent.isRunning) return;
    const next = mergeDocumentTaskActivities(mergeProductMessageUpdates(agent.messages, page.messages), tasks);
    if (next !== agent.messages) { agent.setMessages(next); onChangedRef.current?.(); }
  }, [agent, threadId]);

  const latestUser = agent.messages.findLast((message) => message.role === "user");
  return <section ref={threadElement} className={`${styles.thread} flex min-h-0 flex-1 flex-col`} aria-label="对话">
    <div className={styles.threadToolbar}>
      {hasOlder ? <button className="underline disabled:opacity-40" disabled={loadingOlder || agent.isRunning} onClick={() => void loadOlder()}>{loadingOlder ? "加载中…" : "加载更早消息"}</button> : <span />}
      <details className={styles.threadMenu}>
        <summary aria-label="对话选项" title="对话选项"><svg viewBox="0 0 20 20" width="18" height="18" fill="currentColor" aria-hidden="true"><circle cx="4" cy="10" r="1.4" /><circle cx="10" cy="10" r="1.4" /><circle cx="16" cy="10" r="1.4" /></svg></summary>
        <div className={styles.menuItems}>
          <button type="button" onClick={(event) => { event.currentTarget.closest("details")?.removeAttribute("open"); void refresh(); }}>恢复连接</button>
          {agent.isRunning ? <button type="button" disabled={busy} onClick={(event) => { event.currentTarget.closest("details")?.removeAttribute("open"); void stop(); }}>结束回答</button> : null}
        </div>
      </details>
    </div>
    {error ? <div role="alert" className="mx-4 mb-2 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}
      {retryEdit ? <button type="button" className="ml-3 underline" disabled={agent.isRunning || busy}
        onClick={() => void revise(retryEdit.messageId, retryEdit.text)}>重新回答</button> : null}
    </div> : null}
    {!orderAssistant ? <DocumentTaskSync session={threadId} chatRunning={agent.isRunning} onUpdated={updateDocuments} /> : null}
    {waiting ? <p className="mx-4 mb-2 text-xs text-amber-800">等待批准，请在批准卡片中选择。</p> : null}
    <input ref={fileInput} type="file" multiple className="hidden" onChange={(event) => {
      const files = Array.from(event.target.files ?? []);
      event.target.value = "";
      if (!files.length) return;
      setUploading(true); setError("");
      void Promise.all(files.map((file) => uploadChatResource(file, /^(image|video|audio)\//.test(file.type) ? "REFERENCE" : "ATTACHMENT"))).then((uploaded) => setAttachments((current) => [...current, ...uploaded]))
        .catch((failure) => setError(failure instanceof Error ? failure.message : "上传失败"))
        .finally(() => setUploading(false));
    }} />
    {pickerOpen ? <ChatAttachmentPicker resources={historyReferenceResources(agent.messages)} hasOlder={hasOlder} loadingOlder={loadingOlder}
      onLoadOlder={() => void loadOlder()} onUpload={() => { setPickerOpen(false); fileInput.current?.click(); }}
      onSelect={(resource) => { setAttachments((current) => selectHistoryReference(current, resource)); setPickerOpen(false); }}
      onClose={() => setPickerOpen(false)} /> : null}
    {orderPickerOpen ? <OrderPicker threadId={threadId} onClose={() => setOrderPickerOpen(false)} onReference={setOrderReference} /> : null}
    <OrderReferenceContext.Provider value={setOrderReference}>
    <ResourceReferenceContext.Provider value={{
      selectedIds: new Set(attachments.map((item) => item.resourceId)),
      toggle: (resource) => {
        setAttachments((current) => current.some((item) => item.resourceId === resource.id)
          ? current.filter((item) => item.resourceId !== resource.id) : selectHistoryReference(current, resource));
        if (!attachments.some((item) => item.resourceId === resource.id)) {
          threadElement.current?.querySelector("textarea")?.focus();
        }
      },
    }}>
    <Actions.Provider value={{ submit: (value) => void submit(value), addFile: () => setPickerOpen(true),
      disabled: !isReady || agent.isRunning || uploading || waiting || busy, attachments, uploading,
      removeFile: (id) => setAttachments((current) => current.filter((resource) => resource.resourceId !== id)),
      addOrder: orderAssistant ? () => setOrderPickerOpen(true) : undefined, orderReference, removeOrder: () => setOrderReference(null),
      paused: pausedRunId !== null, pause: () => void pause(), resume: () => void resume(), busy }}>
    <UserMessageEditContext.Provider value={{
      latestId: latestUser && /^\d+$/.test(latestUser.id) ? latestUser.id : null,
      disabled: !isReady || agent.isRunning || uploading || waiting || busy, save: revise,
    }}>
      {isReady ? <CopilotChat agentId={localAgentId} threadId={threadId} chatView={ThreadView} className="min-h-0 flex-1" throttleMs={40}
        onStop={() => void stop()} inspectorTools={false}
        labels={{ chatInputPlaceholder: "输入消息…" }}
        onError={(event) => { if ("error" in event) setError(event.error.message); }} />
        : <div className="grid flex-1 place-items-center text-sm text-stone-500">加载对话…</div>}
    </UserMessageEditContext.Provider>
    </Actions.Provider>
    </ResourceReferenceContext.Provider>
    </OrderReferenceContext.Provider>
  </section>;
}

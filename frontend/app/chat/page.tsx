"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useEffect, useMemo, useRef, useState } from "react";
import {
  applyAgentStep,
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyPersistedMessage,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  removeEmptyAssistantPlaceholders,
  toRenderableTurns,
  toUiChatMessage,
  type UiAgentStep,
  type UiChatMessage,
} from "@/lib/chat-message-state";
import { agentStepStatusText, visibleAgentSteps } from "@/lib/agent-ui";
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";
import { apiStream } from "@/lib/http";
import { getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { SystemPrompt, listSystemPrompts } from "@/lib/system-prompts";
import { buildCallHref } from "@/lib/call-state";
import {
  agentChatHref,
  buildNewSessionPayload,
  buildChatSendPayload,
  isStandardAgent,
  nextSelectedPromptIdForHydratedSession,
  shouldCreateSessionForRequestedAgent,
  STANDARD_AGENT_ID,
} from "@/lib/chat-agent-mode";
import { AgentSummary, listAgents } from "@/lib/agents";
import { MarkdownContent } from "./markdown-content";
import {
  bootstrapChatSession,
  ChatMessageResource,
  ChatSessionOpen,
  ChatSessionSummary,
  activateHistorySession,
  createChatSession,
  getChatSessionMessages,
  listChatHistory,
  resolveChatSession,
} from "@/lib/chat-sessions";

type MessageSegment =
  | {
      type: "text";
      content: string;
    }
  | {
      type: "think";
      content: string;
      complete: boolean;
    };

const starterPrompts = ["主人，你今天工作怎么样呢？", "主人，和我聊聊天吧!", "主人，有什么难题尽管问我哦!"];
const callReturnRefreshKey = "h-agent:call-return-refresh";

function parseMessageSegments(content: string): MessageSegment[] {
  if (!content) return [];

  const segments: MessageSegment[] = [];
  let cursor = 0;

  while (cursor < content.length) {
    const thinkStart = content.indexOf("<think>", cursor);
    if (thinkStart < 0) {
      segments.push({ type: "text", content: content.slice(cursor) });
      break;
    }

    if (thinkStart > cursor) {
      segments.push({ type: "text", content: content.slice(cursor, thinkStart) });
    }

    const thinkContentStart = thinkStart + "<think>".length;
    const thinkEnd = content.indexOf("</think>", thinkContentStart);
    if (thinkEnd < 0) {
      segments.push({
        type: "think",
        content: content.slice(thinkContentStart),
        complete: false,
      });
      break;
    }

    segments.push({
      type: "think",
      content: content.slice(thinkContentStart, thinkEnd),
      complete: true,
    });
    cursor = thinkEnd + "</think>".length;
  }

  return segments.filter((segment) => segment.content);
}

function AssistantMessageContent({ content }: { content: string }) {
  const segments = parseMessageSegments(content);
  if (segments.length === 0) {
    return null;
  }

  return (
    <div className="space-y-3">
      {segments.map((segment, index) => {
        if (segment.type === "text") {
          return <MarkdownContent key={`text-${index}`} content={segment.content} />;
        }

        return (
          <details
            key={`think-${index}`}
            className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600"
          >
            <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
              {segment.complete ? "思考过程" : "思考中..."}
            </summary>
            <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">
              {segment.content}
            </div>
          </details>
        );
      })}
    </div>
  );
}

function BlockedMessageContent({ content }: { content: string }) {
  return (
    <div className="space-y-3">
      <div className="space-y-1">
        <p className="text-sm font-semibold text-amber-900">平台安全拦截</p>
        <p className="text-sm leading-6 text-amber-900/80">
          抱歉，当前消息未通过平台安全审核，已停止继续生成。
        </p>
      </div>
      <div className="rounded-2xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm leading-6 text-amber-900">
        <p className="text-xs font-medium uppercase tracking-[0.18em] text-amber-700">拦截原因</p>
        <p className="mt-1 whitespace-pre-wrap">{content}</p>
      </div>
    </div>
  );
}

function ReasoningDetails({ content, pending = false }: { content: string; pending?: boolean }) {
  return (
    <details
      className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600"
      open={pending}
    >
      <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
        {pending ? "思考中..." : "思考过程"}
      </summary>
      <div className="mt-2 whitespace-pre-wrap text-xs leading-6 text-stone-500">{content}</div>
    </details>
  );
}

function AgentStepDetails({ steps, pending = false }: { steps: UiAgentStep[]; pending?: boolean }) {
  const visibleSteps = visibleAgentSteps(steps);

  if (visibleSteps.length === 0) {
    return null;
  }

  return (
    <details
      className="rounded-2xl border border-stone-200 bg-stone-50/90 px-3 py-2 text-stone-600"
      open={pending}
    >
      <summary className="cursor-pointer list-none text-xs font-medium tracking-[0.18em] text-stone-500">
        子 Agent 状态
      </summary>
      <div className="mt-2 grid gap-2">
        {visibleSteps.map((step) => (
          <div key={step.invocationId} className="flex h-8 items-center justify-between gap-3 rounded-xl border border-stone-200 bg-white px-2 text-xs">
            <span className="min-w-0 truncate font-medium text-stone-700">{step.nodeName}</span>
            <span className="shrink-0 text-stone-500">{agentStepStatusText(step.status)}</span>
          </div>
        ))}
      </div>
    </details>
  );
}

function MediaContent({
  content,
  resources,
}: {
  content: string;
  resources: Array<{
    id: string;
    type: string;
    role: string;
    viewUrl: string;
    downloadUrl: string;
    fileName: string;
    mimeType: string;
    width: number | null;
    height: number | null;
  }>;
}) {
  return (
    <div className="space-y-3">
      {resources.map((resource) => {
        const resourceType = resource.type.toUpperCase();
        if (resourceType === "IMAGE" || resource.mimeType.startsWith("image/")) {
          return (
            <div key={resource.id} className="space-y-2">
              <img
                className="aspect-square w-full rounded-[1.2rem] border border-stone-200 object-cover"
                src={resource.viewUrl}
                alt={content || resource.fileName}
                width={resource.width ?? 1024}
                height={resource.height ?? 1024}
              />
              <div className="flex justify-end">
                <a
                  className="shrink-0 rounded-full bg-stone-900 px-3 py-2 text-xs font-semibold text-white"
                  href={resource.downloadUrl}
                >
                  下载
                </a>
              </div>
            </div>
          );
        }
        if (resourceType === "VIDEO" || resource.mimeType.startsWith("video/")) {
          return <video key={resource.id} src={resource.viewUrl} controls className="w-full rounded-[1.2rem]" />;
        }
        if (resourceType === "AUDIO" || resource.mimeType.startsWith("audio/")) {
          return <audio key={resource.id} src={resource.viewUrl} controls className="w-full" />;
        }
        return (
          <a
            key={resource.id}
            href={resource.downloadUrl}
            className="block rounded-xl border border-stone-200 px-3 py-2 text-sm text-stone-600"
          >
            {resource.fileName}
          </a>
        );
      })}
    </div>
  );
}

function ChatPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const historyContainerRef = useRef<HTMLDivElement | null>(null);
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [bootstrapping, setBootstrapping] = useState(true);
  const [hydratedRouteKey, setHydratedRouteKey] = useState("");
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [resolvingChoice, setResolvingChoice] = useState(false);
  const [error, setError] = useState("");
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [messages, setMessages] = useState<UiChatMessage[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState("新会话");
  const [currentAgentId, setCurrentAgentId] = useState(STANDARD_AGENT_ID);
  const [currentAgentName, setCurrentAgentName] = useState("普通聊天");
  const [agentOptions, setAgentOptions] = useState<AgentSummary[]>([]);
  const [showNewSessionPicker, setShowNewSessionPicker] = useState(false);
  const [creatingNewAgentId, setCreatingNewAgentId] = useState<string | null>(null);
  const [showSessionChooser, setShowSessionChooser] = useState(false);
  const [sessionCandidates, setSessionCandidates] = useState<ChatSessionSummary[]>([]);
  const [historySessions, setHistorySessions] = useState<ChatSessionSummary[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [hasMoreHistory, setHasMoreHistory] = useState(true);
  const [historyLoadedForSession, setHistoryLoadedForSession] = useState<string | null>(null);
  const [hasOlderMessages, setHasOlderMessages] = useState(false);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | null>(null);
  const [pendingResources, setPendingResources] = useState<UploadedResource[]>([]);
  const [uploading, setUploading] = useState(false);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);
  const [attachmentMenuMode, setAttachmentMenuMode] = useState<"menu" | "history">("menu");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const attachmentMenuRef = useRef<HTMLDivElement>(null);
  const requestedAgentId = searchParams.get("agentId");
  const requestedSessionId = searchParams.get("sessionId");
  const routeRequestKey = `${requestedAgentId ?? ""}:${requestedSessionId ?? ""}`;
  const routeBootstrapping = bootstrapping || hydratedRouteKey !== routeRequestKey;

  const generatedImages = useMemo(() => {
    return messages
      .filter((m) => m.messageType === "IMAGE" && m.resources && m.resources.length > 0)
      .flatMap((m) =>
        m.resources!
          .filter((r) => r.type === "IMAGE")
          .map((r) => ({ ...r, messageId: m.id })),
      );
  }, [messages]);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (attachmentMenuRef.current && !attachmentMenuRef.current.contains(event.target as Node)) {
        setShowAttachmentMenu(false);
        setAttachmentMenuMode("menu");
      }
    }
    if (showAttachmentMenu) {
      document.addEventListener("mousedown", handleClickOutside);
    }
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, [showAttachmentMenu]);

  useEffect(() => {
    let cancelled = false;
    let requestedSessionError = "";

    async function activateRequestedSession(currentSessionId: string | null) {
      if (!requestedSessionId) {
        return null;
      }
      try {
        return await activateHistorySession(requestedSessionId, currentSessionId);
      } catch (sessionError) {
        requestedSessionError = sessionError instanceof Error ? sessionError.message : "加载会话失败";
        return null;
      }
    }

    getCurrentUser()
      .then(async () => {
        const [list, bootstrap, agents] = await Promise.all([listSystemPrompts(), bootstrapChatSession(), listAgents()]);
        if (cancelled) return;
        setAuthenticated(true);
        setPrompts(list);
        setAgentOptions(agents.filter((agent) => !isStandardAgent(agent.agentId)));
        const defaultPrompt = list.find((prompt) => prompt.isDefault) ?? list[0] ?? null;
        if (bootstrap.resolution === "choose") {
          if (requestedSessionId) {
            const currentSessionId = bootstrap.candidates[0]?.sessionId ?? null;
            const requested = await activateRequestedSession(currentSessionId);
            if (cancelled) return;
            if (requested) {
              hydrateSession(requested, defaultPrompt?.id ?? null);
              return;
            }
          }
          if (requestedAgentId) {
            const currentSessionId = bootstrap.candidates[0]?.sessionId ?? null;
            const resolved = currentSessionId ? await resolveChatSession(currentSessionId) : null;
            if (cancelled) return;
            const requestedSession = await createChatSession({
              currentSessionId: resolved?.session.sessionId ?? currentSessionId,
              promptId: null,
              agentId: requestedAgentId,
            });
            if (cancelled) return;
            hydrateSession(requestedSession, defaultPrompt?.id ?? null);
            return;
          }
          setSelectedPromptId(defaultPrompt?.id ?? null);
          setSessionCandidates(bootstrap.candidates);
          setShowSessionChooser(true);
          setMessages([]);
          if (requestedSessionError) {
            setError(requestedSessionError);
          }
          return;
        }
        const open = bootstrap.session;
        if (requestedSessionId) {
          const currentSessionId = open?.session.sessionId ?? bootstrap.candidates[0]?.sessionId ?? null;
          const requested = await activateRequestedSession(currentSessionId);
          if (cancelled) return;
          if (requested) {
            hydrateSession(requested, defaultPrompt?.id ?? null);
            return;
          }
        }
        if (
          shouldCreateSessionForRequestedAgent({
            requestedAgentId,
            currentAgentId: open?.session.agentId,
            sessionId: open?.session.sessionId,
          })
        ) {
          const requestedSession = await createChatSession({
            currentSessionId: open?.session.sessionId ?? null,
            promptId: null,
            agentId: requestedAgentId,
          });
          if (cancelled) return;
          hydrateSession(requestedSession, defaultPrompt?.id ?? null);
          return;
        }
        hydrateSession(open, defaultPrompt?.id ?? null);
        if (requestedSessionError) {
          setError(requestedSessionError);
        }
      })
      .catch(() => {
        if (cancelled) return;
        setAuthenticated(false);
        savePostLoginRedirect("/chat");
        router.replace("/auth/login");
      })
      .finally(() => {
        if (!cancelled) {
          setBootstrapping(false);
          setHydratedRouteKey(routeRequestKey);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [requestedAgentId, requestedSessionId, routeRequestKey, router]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const canSubmit = useMemo(
    () => input.trim().length > 0 && !streaming && !routeBootstrapping && !showSessionChooser && !!sessionId,
    [input, routeBootstrapping, sessionId, showSessionChooser, streaming],
  );
  const usingStandardAgent = isStandardAgent(currentAgentId);

  function hydrateSession(open: ChatSessionOpen | null, fallbackPromptId: number | null) {
    if (!open) return;
    const detail = open.session;
    const messagePage = open.messagePage;
    const agentId = detail.agentId || STANDARD_AGENT_ID;
    setSessionId(detail.sessionId);
    setCurrentSessionTitle(detail.title || "新会话");
    setCurrentAgentId(agentId);
    setCurrentAgentName(detail.agentDisplayName || "普通聊天");
    setSelectedPromptId((current) =>
      nextSelectedPromptIdForHydratedSession({
        hydratedAgentId: agentId,
        hydratedPromptId: detail.promptId,
        currentPromptId: current,
        fallbackPromptId,
      }),
    );
    setMessages(messagePage.messages.map(toUiChatMessage));
    setHasOlderMessages(messagePage.hasMore);
    setNextBeforeSeq(messagePage.nextBeforeSeq);
    setShowSessionChooser(false);
    setSessionCandidates([]);
    setHistoryLoadedForSession(null);
    setError("");
  }

  async function refreshCurrentMessages(targetSessionId: string) {
    const detail = await getChatSessionMessages(targetSessionId, 20);
    setMessages(detail.messages.map(toUiChatMessage));
    setHasOlderMessages(detail.hasMore);
    setNextBeforeSeq(detail.nextBeforeSeq);
  }

  useEffect(() => {
    if (!sessionId || routeBootstrapping) {
      return;
    }
    const raw = sessionStorage.getItem(callReturnRefreshKey);
    if (!raw) {
      return;
    }

    let parsed: { sessionId?: string; at?: number };
    try {
      parsed = JSON.parse(raw) as { sessionId?: string; at?: number };
    } catch {
      sessionStorage.removeItem(callReturnRefreshKey);
      return;
    }
    if (parsed.sessionId !== sessionId || !parsed.at || Date.now() - parsed.at > 30_000) {
      sessionStorage.removeItem(callReturnRefreshKey);
      return;
    }

    let cancelled = false;
    const timeouts: ReturnType<typeof setTimeout>[] = [];
    const scheduleRefresh = (delayMs: number) => {
      const timeout = setTimeout(() => {
        if (cancelled) {
          return;
        }
        void refreshCurrentMessages(sessionId).catch(() => undefined);
      }, delayMs);
      timeouts.push(timeout);
    };

    scheduleRefresh(400);
    scheduleRefresh(1600);
    scheduleRefresh(3500);
    sessionStorage.removeItem(callReturnRefreshKey);

    return () => {
      cancelled = true;
      for (const timeout of timeouts) {
        clearTimeout(timeout);
      }
    };
  }, [routeBootstrapping, sessionId]);

  async function loadHistory(reset: boolean) {
    if (!sessionId) return;
    setLoadingHistory(true);
    try {
      const nextPage = reset ? 0 : historyPage;
      const items = await listChatHistory(nextPage, 10);
      setHistorySessions((current) => (reset ? items : [...current, ...items]));
      setHistoryPage(nextPage + 1);
      setHasMoreHistory(items.length === 10);
      setHistoryLoadedForSession(sessionId);
    } finally {
      setLoadingHistory(false);
    }
  }

  async function handleOpenDrawer() {
    setDrawerOpen(true);
    if (historyLoadedForSession === sessionId) return;
    await loadHistory(true);
  }

  async function handleSelectPrompt(promptId: number) {
    if (streaming || selectedPromptId === promptId) return;
    try {
      const detail = await createChatSession({
        currentSessionId: sessionId,
        promptId,
      });
      hydrateSession(detail, promptId);
      setSelectedPromptId(promptId);
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "创建会话失败");
    }
  }

  async function handleChooseSession(selectedSessionId: string) {
    if (resolvingChoice) return;
    setResolvingChoice(true);
    try {
      const detail = await resolveChatSession(selectedSessionId);
      hydrateSession(detail, selectedPromptId);
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "恢复会话失败");
    } finally {
      setResolvingChoice(false);
    }
  }

  async function handleCreateNewSession() {
    if (streaming) return;
    setDrawerOpen(false);
    setShowNewSessionPicker(true);
  }

  async function handleCreateNewSessionForAgent(targetAgentId: string) {
    if (streaming) return;
    setCreatingNewAgentId(targetAgentId);
    try {
      const detail = await createChatSession(buildNewSessionPayload({
        currentSessionId: sessionId,
        targetAgentId,
        promptId: selectedPromptId,
      }));
      hydrateSession(detail, selectedPromptId);
      setShowNewSessionPicker(false);
      router.replace(isStandardAgent(targetAgentId) ? "/chat" : agentChatHref(targetAgentId), { scroll: false });
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "新建会话失败");
    } finally {
      setCreatingNewAgentId(null);
    }
  }

  async function handleOpenHistorySession(targetSessionId: string) {
    if (streaming) return;
    try {
      const detail = await activateHistorySession(targetSessionId, sessionId);
      hydrateSession(detail, detail.session.promptId ?? selectedPromptId);
      setDrawerOpen(false);
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "加载会话失败");
    }
  }

  async function handleLoadOlderMessages() {
    if (!sessionId || loadingOlderMessages || !hasOlderMessages) return;
    const cursor = nextBeforeSeq;
    if (!cursor) return;
    setLoadingOlderMessages(true);
    try {
      const detail = await getChatSessionMessages(sessionId, 20, cursor);
      const olderMessages = detail.messages.map(toUiChatMessage);
      setMessages((current) => [...olderMessages, ...current]);
      setHasOlderMessages(detail.hasMore);
      setNextBeforeSeq(detail.nextBeforeSeq);
      historyContainerRef.current?.scrollTo({ top: 40 });
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "加载更多消息失败");
    } finally {
      setLoadingOlderMessages(false);
    }
  }

  function handleOpenCall() {
    if (!sessionId || streaming || routeBootstrapping) return;
    const callPromptId = isStandardAgent(currentAgentId) ? selectedPromptId : null;
    router.push(buildCallHref(currentAgentId, sessionId, callPromptId));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || streaming || !sessionId) return;

    const messageResources = pendingResources.map((r) => ({
      resourceId: r.resourceId,
      role: r.role,
      source: r.source,
    }));
    const pendingMessageResources: ChatMessageResource[] = pendingResources.map((r) => ({
      id: r.resourceId,
      type: r.type,
      role: r.role,
      viewUrl: r.viewUrl,
      downloadUrl: r.downloadUrl,
      fileName: r.fileName,
      mimeType: r.mimeType,
      fileSize: r.fileSize,
      width: null,
      height: null,
    }));

    const seed = Date.now();
    const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn(
      content,
      seed,
      pendingMessageResources,
    );

    setInput("");
    setPendingResources([]);
    setError("");
    setStreaming(true);
    setMessages((current) => [...current, userMessage, reasoningMessage, assistantMessage]);

    try {
      await apiStream(
        "/api/chat/messages/stream",
        {
          method: "POST",
          body: JSON.stringify(buildChatSendPayload({
            message: content,
            sessionId,
            promptId: selectedPromptId,
            agentId: currentAgentId,
            resources: messageResources.length > 0 ? messageResources : undefined,
          })),
        },
        {
          onUserMessage(message) {
            setMessages((current) => applyPersistedMessage(current, userMessage.id, message));
          },
          onReasoning(chunk) {
            setMessages((current) => applyReasoningChunk(current, reasoningMessage.id, chunk));
          },
          onChunk(chunk) {
            setMessages((current) => applyAssistantChunk(current, assistantMessage.id, chunk));
          },
          onBlocked(message) {
            setMessages((current) => applyBlockedState(current, assistantMessage.id, message));
          },
          onImage(message) {
            setMessages((current) => applyImageMessage(current, assistantMessage.id, message));
          },
          onAgentStep(step) {
            setMessages((current) => applyAgentStep(current, assistantMessage.id, step));
          },
          onDone(_content, message) {
            setMessages((current) => {
              const withPersistedMessage = message
                ? applyPersistedMessage(current, assistantMessage.id, message)
                : current;
              return removeEmptyAssistantPlaceholders(withPersistedMessage);
            });
            setCurrentSessionTitle((current) => (current === "新会话" ? content.slice(0, 20) || current : current));
          },
          onError(message) {
            setError(message);
          },
        },
      );
    } catch (streamError) {
      const message = streamError instanceof Error ? streamError.message : "发送失败";
      setError(message);
      setMessages((current) =>
        current.map((item) =>
          item.id === assistantMessage.id && !item.content
            ? { ...item, content: "暂时无法响应，请稍后重试。" }
            : item,
        ),
      );
      if (message === "Unauthorized") {
        setAuthenticated(false);
        savePostLoginRedirect("/chat");
        router.replace("/auth/login");
      }
    } finally {
      setStreaming(false);
    }
  }

  async function handleLogout() {
    try {
      await logout();
    } finally {
      setAuthenticated(false);
      savePostLoginRedirect("/chat");
      router.replace("/auth/login");
    }
  }

  if (authenticated !== true) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
      {showSessionChooser ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-950/40 px-4">
          <div className="w-full max-w-sm rounded-[2rem] bg-[#f8f5ec] p-6 shadow-[0_24px_80px_rgba(58,45,28,0.22)]">
            <p className="text-xs uppercase tracking-[0.22em] text-amber-700">选择会话</p>
            <h2 className="mt-2 text-xl font-semibold">发现多个未归档会话</h2>
            <p className="mt-2 text-sm leading-6 text-stone-500">请选择一个继续，其他会话会自动归档到历史记录。</p>
            <div className="mt-5 space-y-3">
              {sessionCandidates.map((candidate) => (
                <button
                  key={candidate.sessionId}
                  className="w-full rounded-2xl border border-stone-200 bg-white/80 px-4 py-3 text-left transition hover:border-amber-400"
                  type="button"
                  disabled={resolvingChoice}
                  onClick={() => handleChooseSession(candidate.sessionId)}
                >
                  <p className="text-sm font-semibold text-stone-800">{candidate.title}</p>
                  <p className="mt-1 line-clamp-2 text-sm text-stone-500">
                    {candidate.lastUserMessage || "暂无用户消息"}
                  </p>
                </button>
              ))}
            </div>
          </div>
        </div>
      ) : null}

      {showNewSessionPicker ? (
        <div className="fixed inset-0 z-50 flex items-end bg-stone-950/35 p-4 sm:items-center sm:justify-center">
          <button
            className="absolute inset-0"
            type="button"
            aria-label="关闭新会话选择"
            disabled={creatingNewAgentId !== null}
            onClick={() => setShowNewSessionPicker(false)}
          />
          <div className="relative w-full max-w-md rounded-[1.5rem] bg-[#f8f5ec] p-5 shadow-2xl">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-xs uppercase tracking-[0.2em] text-amber-700">New Chat</p>
                <h2 className="mt-1 text-xl font-semibold">选择新会话</h2>
              </div>
              <button
                className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-600"
                type="button"
                disabled={creatingNewAgentId !== null}
                onClick={() => setShowNewSessionPicker(false)}
              >
                关闭
              </button>
            </div>

            <div className="mt-5 max-h-[70vh] space-y-3 overflow-y-auto pr-1">
              <button
                className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left transition hover:border-amber-400 disabled:opacity-60"
                type="button"
                disabled={creatingNewAgentId !== null}
                onClick={() => handleCreateNewSessionForAgent(STANDARD_AGENT_ID)}
              >
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="text-sm font-semibold text-stone-900">普通聊天</p>
                    <p className="mt-1 text-xs text-stone-500">SystemPrompt / 知识库</p>
                  </div>
                  {creatingNewAgentId === STANDARD_AGENT_ID ? <span className="text-xs text-amber-700">创建中...</span> : null}
                </div>
              </button>

              {agentOptions.map((agent) => (
                <button
                  key={agent.agentId}
                  className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left transition hover:border-amber-400 disabled:opacity-60"
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  onClick={() => handleCreateNewSessionForAgent(agent.agentId)}
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-semibold text-stone-900">{agent.displayName}</p>
                      <p className="mt-1 text-xs text-stone-500">{agent.domain}</p>
                    </div>
                    {creatingNewAgentId === agent.agentId ? (
                      <span className="shrink-0 text-xs text-amber-700">创建中...</span>
                    ) : null}
                  </div>
                </button>
              ))}

              {agentOptions.length === 0 ? (
                <p className="rounded-2xl border border-dashed border-stone-300 px-4 py-6 text-center text-sm text-stone-500">
                  暂无领域 Agent
                </p>
              ) : null}
            </div>
          </div>
        </div>
      ) : null}

      {drawerOpen ? (
        <button
          className="fixed inset-0 z-30 bg-stone-950/30"
          type="button"
          aria-label="关闭菜单遮罩"
          onClick={() => setDrawerOpen(false)}
        />
      ) : null}

      <aside
        className={`fixed bottom-0 left-0 top-0 z-40 flex w-[280px] max-w-[82vw] flex-col justify-between bg-[#f8f5ec] px-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))] pt-[max(1.5rem,env(safe-area-inset-top))] shadow-[24px_0_60px_rgba(58,45,28,0.18)] transition-transform duration-200 ${
          drawerOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div>
          <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h2 className="mt-2 text-2xl font-semibold">菜单</h2>
          <button
            className="mt-5 w-full rounded-2xl bg-stone-900 px-4 py-3 text-sm font-semibold text-white"
            type="button"
            disabled={streaming}
            onClick={handleCreateNewSession}
          >
            新会话
          </button>
          <div className="mt-5">
            <p className="text-xs uppercase tracking-[0.2em] text-stone-400">历史会话</p>
            <div className="mt-3 max-h-[48vh] space-y-3 overflow-y-auto pr-1">
              {historySessions.map((session) => (
                <button
                  key={session.sessionId}
                  className={`w-full rounded-2xl border px-4 py-3 text-left ${
                    session.sessionId === sessionId
                      ? "border-stone-900 bg-stone-900 text-white"
                      : "border-stone-200 bg-white/75 text-stone-700"
                  }`}
                  type="button"
                  onClick={() => handleOpenHistorySession(session.sessionId)}
                >
                  <p className="text-sm font-semibold">{session.title}</p>
                  <p className="mt-1 line-clamp-2 text-xs opacity-75">
                    {session.lastUserMessage || "暂无用户消息"}
                  </p>
                </button>
              ))}
              {loadingHistory ? <p className="text-sm text-stone-500">加载中...</p> : null}
              {!loadingHistory && hasMoreHistory ? (
                <button
                  className="w-full rounded-2xl border border-dashed border-stone-300 px-4 py-3 text-sm text-stone-600"
                  type="button"
                  onClick={() => loadHistory(false)}
                >
                  加载更多
                </button>
              ) : null}
            </div>
          </div>
        </div>

        <div className="space-y-3">
          <Link
            className="block rounded-2xl bg-stone-900 px-4 py-4 text-sm font-semibold text-white"
            href="/me"
            onClick={() => setDrawerOpen(false)}
          >
            我的
          </Link>
          <button
            className="w-full rounded-2xl border border-stone-200 bg-white/70 px-4 py-4 text-left text-sm font-semibold text-stone-700"
            type="button"
            onClick={handleLogout}
          >
            退出登录
          </button>
        </div>
      </aside>

      <section className="mx-auto flex min-h-screen w-full max-w-md flex-col">
        <header className="sticky top-0 z-10 border-b border-stone-200/80 bg-[#f7f4ea]/95 px-4 pb-4 pt-[max(1rem,env(safe-area-inset-top))] backdrop-blur">
          <div className="flex items-center gap-3">
            <button
              className="flex h-11 w-11 shrink-0 flex-col items-center justify-center gap-1.5 rounded-full border border-stone-300 bg-white/80 transition hover:bg-stone-100"
              type="button"
              aria-label="打开菜单"
              onClick={() => void handleOpenDrawer()}
            >
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
            </button>
            <div className="min-w-0 flex-1">
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent Chat</p>
              <h1 className="mt-2 truncate text-xl font-semibold">{currentSessionTitle}</h1>
            </div>
            <button
              className="shrink-0 rounded-full border border-stone-300 bg-white/80 px-4 py-2 text-sm font-semibold text-stone-700 transition hover:bg-stone-100 disabled:cursor-not-allowed disabled:opacity-50"
              type="button"
              disabled={!sessionId || streaming || routeBootstrapping}
              onClick={handleOpenCall}
            >
              电话
            </button>
          </div>
        </header>

        <div ref={historyContainerRef} className="flex-1 overflow-y-auto px-4 pb-36 pt-5">
          {usingStandardAgent ? (
            <div className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <div>
                  <p className="text-xs uppercase tracking-[0.2em] text-amber-700">SystemPrompt</p>
                  <p className="mt-1 text-sm text-stone-500">选择当前对话使用的系统提示词</p>
                </div>
                <div className="flex shrink-0 items-center gap-3">
                  {selectedPromptId ? (
                    <Link className="text-sm font-medium text-amber-700" href={`/me/knowledge?promptId=${selectedPromptId}`}>
                      知识库
                    </Link>
                  ) : null}
                  <Link className="text-sm font-medium text-amber-700" href="/me/system-prompts">
                    管理
                  </Link>
                </div>
              </div>
              <div className="mt-3 flex gap-2 overflow-x-auto pb-1">
                {prompts.map((prompt) => (
                  <button
                    key={prompt.id}
                    className={`shrink-0 rounded-full border px-4 py-2 text-sm shadow-sm ${
                      prompt.id === selectedPromptId
                        ? "border-stone-900 bg-stone-900 text-white"
                        : "border-stone-200 bg-white text-stone-600"
                    }`}
                    type="button"
                    disabled={streaming}
                    onClick={() => handleSelectPrompt(prompt.id)}
                  >
                    {prompt.name}
                    {prompt.isDefault ? " · 默认" : ""}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            <div className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-xs uppercase tracking-[0.2em] text-amber-700">Domain Agent</p>
                  <p className="mt-1 truncate text-sm font-semibold text-stone-800">{currentAgentName}</p>
                </div>
                <Link className="shrink-0 text-sm font-medium text-amber-700" href={`/me/agents/${encodeURIComponent(currentAgentId)}`}>
                  拓扑详情
                </Link>
              </div>
            </div>
          )}

          {hasOlderMessages ? (
            <div className="mt-4 text-center">
              <button
                className="rounded-full border border-stone-200 bg-white/85 px-4 py-2 text-sm text-stone-600"
                type="button"
                disabled={loadingOlderMessages}
                onClick={handleLoadOlderMessages}
              >
                {loadingOlderMessages ? "加载中..." : "加载更早消息"}
              </button>
            </div>
          ) : null}

          <div className="mt-4 flex gap-2 overflow-x-auto pb-1">
            {starterPrompts.map((prompt) => (
              <button
                key={prompt}
                className="shrink-0 rounded-full border border-stone-200 bg-white/90 px-4 py-2 text-sm text-stone-600 shadow-sm"
                type="button"
                onClick={() => setInput(prompt)}
              >
                {prompt}
              </button>
            ))}
          </div>

          <div className="mt-5 space-y-4">
            {!bootstrapping && messages.length === 0 ? (
              <article className="flex justify-start">
                <div className="max-w-[85%] rounded-[1.5rem] rounded-bl-md border border-stone-200 bg-white/95 px-4 py-3 text-sm leading-6 text-stone-700 shadow-sm">
                  你好，我是 嘿 。现在可以开始新的对话了！
                </div>
              </article>
            ) : null}
            {toRenderableTurns(messages).map((turn) => (
              <article
                key={turn.id}
                className={`flex ${turn.kind === "user" ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={[
                    "max-w-[85%] rounded-[1.5rem] px-4 py-3 text-sm leading-6 shadow-sm",
                    turn.kind === "user"
                      ? "rounded-br-md bg-stone-900 text-stone-50"
                      : turn.kind === "blocked"
                        ? "rounded-bl-md border border-amber-200 bg-amber-50/95 text-amber-900"
                        : turn.kind === "image"
                          ? "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700"
                        : "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700",
                  ].join(" ")}
                >
                  {turn.kind === "user" ? (
                    <div className="space-y-3">
                      {turn.resources && turn.resources.length > 0 ? (
                        <MediaContent content={turn.content} resources={turn.resources} />
                      ) : null}
                      {turn.content ? <p className="whitespace-pre-wrap">{turn.content}</p> : null}
                    </div>
                  ) : turn.kind === "blocked" ? (
                    <div className="space-y-3">
                      <AgentStepDetails steps={turn.agentSteps} />
                      {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                      <BlockedMessageContent content={turn.blocked} />
                      {turn.resources && turn.resources.length > 0 ? (
                        <div className="mt-3">
                          <MediaContent content={turn.answer} resources={turn.resources} />
                        </div>
                      ) : null}
                    </div>
                  ) : turn.kind === "image" ? (
                    <MediaContent content={turn.content} resources={turn.resources} />
                  ) : turn.answer || turn.resources.length > 0 ? (
                    <div className="space-y-3">
                      <AgentStepDetails steps={turn.agentSteps} />
                      {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                      {turn.answer ? <AssistantMessageContent content={turn.answer} /> : null}
                      {turn.resources && turn.resources.length > 0 ? (
                        <div className="mt-3">
                          <MediaContent content={turn.answer} resources={turn.resources} />
                        </div>
                      ) : null}
                    </div>
                  ) : turn.reasoning ? (
                    <div className="space-y-3">
                      <AgentStepDetails steps={turn.agentSteps} pending />
                      <ReasoningDetails content={turn.reasoning} pending />
                    </div>
                  ) : (
                    <div className="space-y-3">
                      <AgentStepDetails steps={turn.agentSteps} pending />
                      {streaming ? "正在思考..." : ""}
                    </div>
                  )}
                </div>
              </article>
            ))}
            <div ref={messageEndRef} />
          </div>
        </div>

        <div className="fixed bottom-0 left-0 right-0 mx-auto w-full max-w-md bg-transparent px-4 pb-[calc(1rem+env(safe-area-inset-bottom))]">
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp,video/mp4,audio/mpeg,audio/mp4,audio/wav,audio/webm"
            className="hidden"
            onChange={async (e) => {
              const file = e.target.files?.[0];
              if (!file) return;
              if (file.size > 10 * 1024 * 1024) {
                setError("文件大小不能超过 10MB");
                return;
              }
              setUploading(true);
              setError("");
              try {
                const result = await uploadChatResource(file, sessionId!, "ATTACHMENT");
                setPendingResources((prev) => [...prev, result]);
              } catch (err) {
                setError(err instanceof Error ? err.message : "上传失败");
              } finally {
                setUploading(false);
                e.target.value = "";
              }
            }}
          />
          <div className="relative">
            {showAttachmentMenu ? (
              <div
                ref={attachmentMenuRef}
                className="absolute bottom-full left-0 mb-2 w-full rounded-[1.2rem] border border-stone-200 bg-[#f8f5ec]/95 p-3 shadow-[0_-8px_30px_rgba(58,45,28,0.12)] backdrop-blur"
              >
                {attachmentMenuMode === "menu" ? (
                  <div className="space-y-2">
                    <button
                      type="button"
                      className="w-full rounded-xl bg-white px-3 py-2 text-left text-sm text-stone-700 transition hover:bg-stone-100"
                      onClick={() => {
                        fileInputRef.current?.click();
                        setShowAttachmentMenu(false);
                      }}
                    >
                      上传本地图片
                    </button>
                    <button
                      type="button"
                      className="w-full rounded-xl bg-white px-3 py-2 text-left text-sm text-stone-700 transition hover:bg-stone-100"
                      onClick={() => setAttachmentMenuMode("history")}
                    >
                      从历史选择
                    </button>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex items-center justify-between">
                      <p className="text-xs font-medium text-stone-500">选择历史图片</p>
                      <button
                        type="button"
                        className="text-xs text-amber-700"
                        onClick={() => setAttachmentMenuMode("menu")}
                      >
                        返回
                      </button>
                    </div>
                    <div className="grid max-h-40 grid-cols-4 gap-2 overflow-y-auto">
                      {generatedImages.map((resource) => (
                        <button
                          key={`${resource.messageId}-${resource.id}`}
                          type="button"
                          className="relative aspect-square overflow-hidden rounded-xl border border-stone-200"
                          onClick={() => {
                            setPendingResources((prev) => [
                              ...prev,
                              {
                                resourceId: resource.id,
                                type: resource.type,
                                role: "REFERENCE",
                                source: "HISTORY",
                                viewUrl: resource.viewUrl,
                                downloadUrl: resource.downloadUrl,
                                fileName: resource.fileName,
                                mimeType: resource.mimeType,
                                fileSize: resource.fileSize ?? 0,
                              },
                            ]);
                            setShowAttachmentMenu(false);
                            setAttachmentMenuMode("menu");
                          }}
                        >
                          <img
                            src={resource.viewUrl}
                            alt={resource.fileName}
                            className="h-full w-full object-cover"
                          />
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ) : null}
            <div className="rounded-[2rem] border border-stone-200 bg-[#f8f5ec]/95 p-3 shadow-[0_-8px_30px_rgba(58,45,28,0.12)] backdrop-blur">
              {error ? <p className="px-2 pb-2 text-sm text-red-600">{error}</p> : null}
              {pendingResources.length > 0 ? (
                <div className="flex gap-2 px-2 pb-2">
                  {pendingResources.map((r) => (
                    <div key={r.resourceId} className="relative h-16 w-16">
                      <img
                        src={r.viewUrl}
                        alt={r.fileName}
                        className="h-full w-full rounded-lg border border-stone-200 object-cover"
                      />
                      <button
                        type="button"
                        className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-stone-800 text-xs text-white"
                        onClick={() =>
                          setPendingResources((prev) => prev.filter((p) => p.resourceId !== r.resourceId))
                        }
                      >
                        ×
                      </button>
                    </div>
                  ))}
                </div>
              ) : null}

              <form className="flex items-end gap-3" onSubmit={handleSubmit}>
                <button
                  type="button"
                  className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full border border-stone-200 bg-white text-stone-600 transition hover:bg-stone-100 disabled:opacity-60"
                  onClick={() => {
                    if (generatedImages.length > 0) {
                      setShowAttachmentMenu(true);
                      setAttachmentMenuMode("menu");
                    } else {
                      fileInputRef.current?.click();
                    }
                  }}
                  disabled={uploading || streaming}
                >
                  {uploading ? "..." : "📎"}
                </button>
                <textarea
                  className="max-h-32 min-h-12 flex-1 resize-none rounded-[1.4rem] border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                  placeholder={usingStandardAgent ? "输入你想聊的内容..." : `询问 ${currentAgentName}...`}
                  rows={1}
                />
                <button
                  className="h-12 shrink-0 rounded-full bg-stone-900 px-5 text-sm font-semibold text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-400"
                  type="submit"
                  disabled={!canSubmit}
                >
                  {streaming ? "生成中" : "发送"}
                </button>
              </form>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}

export default function ChatPage() {
  return (
    <Suspense fallback={<main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />}>
      <ChatPageContent />
    </Suspense>
  );
}

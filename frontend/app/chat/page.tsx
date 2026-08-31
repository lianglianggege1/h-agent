"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  applyAgentStep,
  applyAssistantChunk,
  applyBlockedState,
  applyImageMessage,
  applyPersistedMessage,
  applyReasoningChunk,
  buildPendingAssistantTurn,
  hasPendingVideoGeneration,
  removeEmptyAssistantPlaceholders,
  toRenderableTurns,
  toUiChatMessage,
  toUiChatMessages,
  type UiAgentStep,
  type UiChatMessage,
} from "@/lib/chat-message-state";
import {
  agentStepStatusText,
  currentAgentStepText,
  isVisibleSubagentTurn,
  visibleAgentSteps,
} from "@/lib/agent-ui";
import { uploadChatResource, type UploadedResource } from "@/lib/resource-upload";
import { apiStream } from "@/lib/http";
import { getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { SystemPrompt, listSystemPrompts } from "@/lib/system-prompts";
import { buildCallHref } from "@/lib/call-state";
import {
  agentModeFromSession,
  buildNewSessionPayload,
  buildChatSendPayload,
  chatSessionHref,
  domainAgentsFromCatalog,
  filterDomainAgents,
  HARNESS_AGENT_ID,
  isStandardAgent,
  nextSelectedPromptIdForHydratedSession,
  shouldCreateSessionForRequestedAgent,
  STANDARD_RUNTIME_TYPE,
  STANDARD_AGENT_ID,
} from "@/lib/chat-agent-mode";
import { AgentSummary, listAgents } from "@/lib/agents";
import { scrollTopAfterPrepend, type PrependScrollSnapshot } from "@/lib/chat-scroll";
import {
  applyHarnessEvent,
  applyHarnessTranscriptEvent,
  canSubmitSubagentStatus,
  createHarnessUiState,
  harnessTranscriptSessionId,
  harnessTranscriptStreamingState,
  mergePersistedHarnessMessages,
  replaceHarnessSubagents,
  orderedHarnessSubagents,
} from "@/lib/harness-subagent-state";
import {
  buildSubagentMessageRequest,
  getHarnessSubagents,
  observeHarnessSubagentEvents,
} from "@/lib/harness-subagent-api";
import type { HarnessSubagentStatus } from "@/lib/harness-subagent-types";
import {
  approvalDecisionPath,
  approvalModeOptions,
  getPendingApproval,
  type ApprovalDecision,
  type ApprovalMode,
  type ApprovalRequest,
} from "@/lib/harness-approval";
import { MarkdownContent } from "@/components/markdown-content";
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

type SubagentTextAnimation = {
  queue: string[];
  timer: ReturnType<typeof setTimeout> | null;
  receivedDelta: boolean;
  waiters: Array<() => void>;
};

const starterPrompts = ["主人，你今天工作怎么样呢？", "主人，和我聊聊天吧!", "主人，有什么难题尽管问我哦!"];
const callReturnRefreshKey = "h-agent:call-return-refresh";

const harnessStatusLabel: Record<HarnessSubagentStatus, string> = {
  AVAILABLE: "可对话",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  FAILED: "失败",
};

function harnessStatusTone(status: HarnessSubagentStatus) {
  if (status === "COMPLETED") return "bg-emerald-500";
  if (status === "RUNNING") return "bg-amber-500";
  if (status === "FAILED") return "bg-red-500";
  return "bg-sky-500";
}

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

function ApprovalCard({
  request,
  deciding,
  onDecision,
}: {
  request: ApprovalRequest;
  deciding: boolean;
  onDecision: (decision: ApprovalDecision) => void;
}) {
  return (
    <article className="flex justify-start" aria-live="polite">
      <div className="w-full rounded-[1.5rem] border border-amber-300 bg-amber-50 px-4 py-4 text-sm text-stone-700 shadow-sm">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-amber-700">需要你的批准</p>
            <p className="mt-2 font-semibold text-stone-900">Agent 请求执行以下操作</p>
          </div>
          <span className="rounded-full bg-white px-2.5 py-1 text-[11px] font-medium text-stone-500">
            {approvalModeOptions.find((item) => item.value === request.approvalMode)?.label ?? request.approvalMode}
          </span>
        </div>
        <div className="mt-3 space-y-2">
          {request.actions.map((action) => (
            <div key={action.toolCallId} className="rounded-xl border border-amber-200 bg-white/80 px-3 py-2">
              <p className="font-mono text-xs font-semibold text-stone-800">{action.toolName}</p>
              <p className="mt-1 text-xs leading-5 text-stone-500">{action.summary}</p>
            </div>
          ))}
        </div>
        <p className="mt-3 text-xs leading-5 text-stone-500">参数内容已由服务端隐藏；批准或拒绝后会继续同一个运行。</p>
        <div className="mt-4 grid grid-cols-2 gap-2">
          <button
            type="button"
            className="h-11 rounded-full border border-stone-300 bg-white font-semibold text-stone-700 disabled:opacity-50"
            disabled={deciding}
            onClick={() => onDecision("DENY")}
          >
            {deciding ? "处理中..." : "拒绝"}
          </button>
          <button
            type="button"
            className="h-11 rounded-full bg-stone-900 font-semibold text-white disabled:opacity-50"
            disabled={deciding}
            onClick={() => onDecision("APPROVE")}
          >
            {deciding ? "处理中..." : "允许执行"}
          </button>
        </div>
      </div>
    </article>
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

function PendingAssistantStatus({ steps, streaming }: { steps: UiAgentStep[]; streaming: boolean }) {
  if (!streaming) {
    return null;
  }

  return <p>{currentAgentStepText(steps) ?? "正在思考..."}</p>;
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
          return (
            <div key={resource.id} className="space-y-2">
              <video src={resource.viewUrl} controls className="w-full rounded-[1.2rem]" />
              <div className="flex justify-end">
                <a
                  className="shrink-0 rounded-full bg-stone-900 px-3 py-2 text-xs font-semibold text-white"
                  href={resource.downloadUrl}
                  download={resource.fileName}
                >
                  下载
                </a>
              </div>
            </div>
          );
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
  const pendingPrependScrollRef = useRef<PrependScrollSnapshot | null>(null);
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [activeSubagentSessionId, setActiveSubagentSessionId] = useState<string | null>(null);
  const [subagentInputBySession, setSubagentInputBySession] = useState<Record<string, string>>({});
  const [subagentStreamingBySession, setSubagentStreamingBySession] = useState<Record<string, boolean>>({});
  const [subagentErrorBySession, setSubagentErrorBySession] = useState<Record<string, string>>({});
  const [subagentUploadingBySession, setSubagentUploadingBySession] = useState<Record<string, boolean>>({});
  const [bootstrapping, setBootstrapping] = useState(true);
  const [hydratedRouteKey, setHydratedRouteKey] = useState("");
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [resolvingChoice, setResolvingChoice] = useState(false);
  const [error, setError] = useState("");
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [messages, setMessages] = useState<UiChatMessage[]>([]);
  const [subagentMessagesBySession, setSubagentMessagesBySession] = useState<Record<string, UiChatMessage[]>>({});
  const [harnessUiState, setHarnessUiState] = useState(createHarnessUiState);
  const [subagentPaginationBySession, setSubagentPaginationBySession] = useState<Record<string, {
    loading: boolean;
    hasMore: boolean;
    nextBeforeSeq: number | null;
  }>>({});
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState("新会话");
  const [currentAgentId, setCurrentAgentId] = useState(STANDARD_AGENT_ID);
  const [currentAgentName, setCurrentAgentName] = useState("通用助手");
  const [currentRuntimeType, setCurrentRuntimeType] = useState(STANDARD_RUNTIME_TYPE);
  const [agentCatalog, setAgentCatalog] = useState<AgentSummary[]>([]);
  const [showNewSessionPicker, setShowNewSessionPicker] = useState(false);
  const [newSessionPickerStep, setNewSessionPickerStep] = useState<"types" | "domain" | "approval">("types");
  const [selectedApprovalMode, setSelectedApprovalMode] = useState<ApprovalMode>("DEFAULT");
  const [domainAgentSearch, setDomainAgentSearch] = useState("");
  const [selectedAgentDomain, setSelectedAgentDomain] = useState("全部");
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
  const [subagentPendingResourcesBySession, setSubagentPendingResourcesBySession] = useState<Record<string, UploadedResource[]>>({});
  const [uploading, setUploading] = useState(false);
  const [currentApprovalMode, setCurrentApprovalMode] = useState<ApprovalMode | null>(null);
  const [pendingApprovalBySession, setPendingApprovalBySession] = useState<Record<string, ApprovalRequest | null>>({});
  const [decidingApprovalId, setDecidingApprovalId] = useState<string | null>(null);
  const [showAttachmentMenu, setShowAttachmentMenu] = useState(false);
  const [attachmentMenuMode, setAttachmentMenuMode] = useState<"menu" | "history">("menu");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const subagentFileInputRef = useRef<HTMLInputElement>(null);
  const attachmentMenuRef = useRef<HTMLDivElement>(null);
  const subagentTextAnimationsRef = useRef<Map<string, SubagentTextAnimation>>(new Map());
  const seenHarnessTranscriptEventsRef = useRef<Set<string>>(new Set());
  const directSubagentStreamsRef = useRef<Set<string>>(new Set());
  const applyHarnessRuntimeEventRef = useRef<(
    payload: Parameters<typeof applyHarnessEvent>[1],
    projectHarnessState?: boolean,
  ) => void>(() => {});
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

  const domainAgents = useMemo(() => domainAgentsFromCatalog(agentCatalog), [agentCatalog]);
  const agentDomains = useMemo(
    () => ["全部", ...Array.from(new Set(domainAgents.map((agent) => agent.domain)))],
    [domainAgents],
  );
  const filteredDomainAgents = useMemo(
    () => filterDomainAgents(domainAgents, domainAgentSearch, selectedAgentDomain),
    [domainAgentSearch, domainAgents, selectedAgentDomain],
  );
  const currentAgentMode = agentModeFromSession({
    runtimeType: currentRuntimeType,
    agentId: currentAgentId,
  });
  const usingStandardAgent = currentAgentMode === "standard";
  const usingHarnessAgent = currentAgentMode === "harness";
  // 顶级父页只展示自己的直接协作者；完整快照仍保留孙级节点供抽屉继续下钻。
  const harnessSubagents = orderedHarnessSubagents(harnessUiState).filter(
    (subagent) => subagent.parentSessionId === sessionId,
  );
  const activeSubagent = usingHarnessAgent && activeSubagentSessionId
    ? harnessUiState.subagentsBySession[activeSubagentSessionId] ?? null
    : null;
  const subagentMessages = activeSubagentSessionId
    ? subagentMessagesBySession[activeSubagentSessionId] ?? []
    : [];
  const subagentStreaming = activeSubagentSessionId
    ? Boolean(subagentStreamingBySession[activeSubagentSessionId])
    : false;
  const activeSubagentPagination = activeSubagentSessionId
    ? subagentPaginationBySession[activeSubagentSessionId]
    : undefined;
  const loadingSubagentMessages = Boolean(activeSubagentPagination?.loading);
  const hasOlderSubagentMessages = Boolean(activeSubagentPagination?.hasMore);
  const nextSubagentBeforeSeq = activeSubagentPagination?.nextBeforeSeq ?? null;
  const subagentPendingResources = activeSubagentSessionId
    ? subagentPendingResourcesBySession[activeSubagentSessionId] ?? []
    : [];
  const subagentInput = activeSubagentSessionId
    ? subagentInputBySession[activeSubagentSessionId] ?? ""
    : "";
  const subagentError = activeSubagentSessionId
    ? subagentErrorBySession[activeSubagentSessionId] ?? ""
    : "";
  const subagentUploading = activeSubagentSessionId
    ? Boolean(subagentUploadingBySession[activeSubagentSessionId])
    : false;
  const rootPendingApproval = sessionId ? pendingApprovalBySession[sessionId] ?? null : null;
  const subagentPendingApproval = activeSubagentSessionId
    ? pendingApprovalBySession[activeSubagentSessionId] ?? null
    : null;

  useEffect(() => {
    if (!sessionId || streaming || !hasPendingVideoGeneration(messages)) {
      return;
    }

    const refreshGeneratedVideoMessages = async () => {
      try {
        const page = await getChatSessionMessages(sessionId, 100);
        setMessages(toUiChatMessages(page.messages));
      } catch {
        // The next polling interval can recover from transient refresh failures.
      }
    };
    const intervalId = window.setInterval(refreshGeneratedVideoMessages, 10_000);
    return () => window.clearInterval(intervalId);
  }, [messages, sessionId, streaming]);

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
        setAgentCatalog(agents);
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
            router.replace(chatSessionHref(requestedSession.session.sessionId), { scroll: false });
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
          router.replace(chatSessionHref(requestedSession.session.sessionId), { scroll: false });
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

  useLayoutEffect(() => {
    const container = historyContainerRef.current;
    const pendingPrependScroll = pendingPrependScrollRef.current;
    if (container && pendingPrependScroll) {
      container.scrollTop = scrollTopAfterPrepend({
        ...pendingPrependScroll,
        nextScrollHeight: container.scrollHeight,
      });
      pendingPrependScrollRef.current = null;
      return;
    }
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const canSubmit = useMemo(
    () => input.trim().length > 0
      && !streaming
      && !routeBootstrapping
      && !showSessionChooser
      && !rootPendingApproval
      && !!sessionId,
    [input, rootPendingApproval, routeBootstrapping, sessionId, showSessionChooser, streaming],
  );

  const canSubmitSubagent = Boolean(
    activeSubagent
      && subagentInput.trim().length > 0
      && !subagentStreaming
      && !subagentPendingApproval
      && canSubmitSubagentStatus(activeSubagent.status),
  );

  useEffect(() => {
    if (!activeSubagentSessionId || routeBootstrapping) {
      return;
    }
    // 会话切换由 hydrateSession 主动关闭抽屉；这里仅负责同步有效子会话的外部历史。
    // 若实时快照暂时还没有该节点，保持选择意图但不触发额外渲染。
    if (!sessionId || !usingHarnessAgent || !activeSubagent) return;

    let cancelled = false;
    getChatSessionMessages(activeSubagent.sessionId, 20)
      .then((page) => {
        if (cancelled) return;
        setSubagentMessagesBySession((current) => ({
          ...current,
          [activeSubagent.sessionId]: mergePersistedHarnessMessages(
            page.messages.map(toUiChatMessage),
            current[activeSubagent.sessionId] ?? [],
            hasPendingSubagentText(activeSubagent.sessionId),
          ),
        }));
        setSubagentPaginationBySession((current) => ({
          ...current,
          [activeSubagent.sessionId]: {
            loading: false,
            hasMore: page.hasMore,
            nextBeforeSeq: page.nextBeforeSeq,
          },
        }));
        setError("");
      })
      .catch((loadError) => {
        if (cancelled) return;
        setSubagentErrorBySession((current) => ({
          ...current,
          [activeSubagent.sessionId]: loadError instanceof Error ? loadError.message : "子对话加载失败",
        }));
      })
      .finally(() => {
        if (!cancelled) {
          setSubagentPaginationBySession((current) => ({
            ...current,
            [activeSubagent.sessionId]: {
              loading: false,
              hasMore: current[activeSubagent.sessionId]?.hasMore ?? false,
              nextBeforeSeq: current[activeSubagent.sessionId]?.nextBeforeSeq ?? null,
            },
          }));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [activeSubagent, activeSubagentSessionId, routeBootstrapping, sessionId, usingHarnessAgent]);

  useEffect(() => {
    if (!usingHarnessAgent || routeBootstrapping || !sessionId) return;
    let cancelled = false;
    void getPendingApproval(sessionId)
      .then((request) => {
        if (!cancelled) setPendingApprovalBySession((current) => ({ ...current, [sessionId]: request }));
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [routeBootstrapping, sessionId, usingHarnessAgent]);

  useEffect(() => {
    if (!usingHarnessAgent || routeBootstrapping || !activeSubagentSessionId) return;
    const childSessionId = activeSubagentSessionId;
    let cancelled = false;
    void getPendingApproval(childSessionId)
      .then((request) => {
        if (!cancelled) setPendingApprovalBySession((current) => ({ ...current, [childSessionId]: request }));
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [activeSubagentSessionId, routeBootstrapping, usingHarnessAgent]);

  useEffect(() => {
    if (!sessionId || !activeSubagent || routeBootstrapping || !usingHarnessAgent
      || activeSubagent.status !== "RUNNING"
      || directSubagentStreamsRef.current.has(activeSubagent.sessionId)) {
      return;
    }
    const childSessionId = activeSubagent.sessionId;
    const controller = new AbortController();
    let stopped = false;
    setSubagentStreamingBySession((current) => ({ ...current, [childSessionId]: true }));

    const observe = async () => {
      while (!stopped) {
        try {
          await observeHarnessSubagentEvents(
            childSessionId,
            (payload) => applyHarnessRuntimeEventRef.current(payload, false),
            controller.signal,
          );
          break;
        } catch (observeError) {
          if (controller.signal.aborted || stopped) return;
          setSubagentErrorBySession((current) => ({
            ...current,
            [childSessionId]: observeError instanceof Error
              ? observeError.message
              : "子对话实时连接失败，正在重连",
          }));
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
      }
      if (stopped) return;
      try {
        const [page] = await Promise.all([
          getChatSessionMessages(childSessionId, 100),
          refreshHarnessState(sessionId),
        ]);
        if (!stopped) {
          setSubagentMessagesBySession((current) => ({
            ...current,
            [childSessionId]: mergePersistedHarnessMessages(
              page.messages.map(toUiChatMessage),
              current[childSessionId] ?? [],
              hasPendingSubagentText(childSessionId),
            ),
          }));
          setSubagentErrorBySession((current) => ({ ...current, [childSessionId]: "" }));
        }
      } catch {
        // 已收到终态；若最佳努力刷新失败，保留实时转录，下一次进入会从历史恢复。
      }
    };
    void observe().finally(() => {
      if (!stopped) {
        setSubagentStreamingBySession((current) => ({ ...current, [childSessionId]: false }));
      }
    });

    return () => {
      stopped = true;
      controller.abort();
    };
  }, [activeSubagent, routeBootstrapping, sessionId, usingHarnessAgent]);

  function hydrateSession(open: ChatSessionOpen | null, fallbackPromptId: number | null) {
    if (!open) return;
    const detail = open.session;
    const messagePage = open.messagePage;
    const agentId = detail.agentId || STANDARD_AGENT_ID;
    setSessionId(detail.sessionId);
    setCurrentSessionTitle(detail.title || "新会话");
    setCurrentAgentId(agentId);
    setCurrentAgentName(detail.agentDisplayName || "通用助手");
    setCurrentRuntimeType(detail.runtimeType || STANDARD_RUNTIME_TYPE);
    setCurrentApprovalMode(detail.approvalMode ?? null);
    setSelectedPromptId((current) =>
      nextSelectedPromptIdForHydratedSession({
        hydratedAgentId: agentId,
        hydratedPromptId: detail.promptId,
        currentPromptId: current,
        fallbackPromptId,
      }),
    );
    setMessages(messagePage.messages.map(toUiChatMessage));
    setSubagentMessagesBySession({});
    setSubagentStreamingBySession({});
    setSubagentPaginationBySession({});
    setSubagentPendingResourcesBySession({});
    setSubagentInputBySession({});
    setSubagentErrorBySession({});
    setSubagentUploadingBySession({});
    setActiveSubagentSessionId(null);
    setHarnessUiState(
      open.subagents
        ? replaceHarnessSubagents(createHarnessUiState(), open.subagents)
        : createHarnessUiState(),
    );
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

  async function refreshHarnessState(targetSessionId: string) {
    const subagents = await getHarnessSubagents(targetSessionId);
    setHarnessUiState((current) => replaceHarnessSubagents(current, subagents));
  }

  function applyHarnessRuntimeEvent(
    payload: Parameters<typeof applyHarnessEvent>[1],
    projectHarnessState = true,
  ) {
    if (projectHarnessState) {
      setHarnessUiState((current) => applyHarnessEvent(current, payload));
    }
    const transcriptEventKey = `${payload.eventType}:${payload.eventId}`;
    if (seenHarnessTranscriptEventsRef.current.has(transcriptEventKey)) return;
    seenHarnessTranscriptEventsRef.current.add(transcriptEventKey);
    if (seenHarnessTranscriptEventsRef.current.size > 10_000) {
      const oldest = seenHarnessTranscriptEventsRef.current.values().next().value;
      if (oldest) seenHarnessTranscriptEventsRef.current.delete(oldest);
    }
    const childSessionId = harnessTranscriptSessionId(payload);
    if (!childSessionId) return;

    const replyId = typeof payload.correlation?.replyId === "string"
      ? payload.correlation.replyId.trim()
      : "";
    const animationMessageId = replyId ? `runtime-assistant-${replyId}` : "";
    const isTextDelta = payload.eventType === "TEXT_BLOCK_DELTA" && Boolean(animationMessageId);
    const isResult = payload.eventType === "AGENT_RESULT" && Boolean(animationMessageId);
    const animationKey = animationMessageId
      ? subagentAnimationKey(childSessionId, animationMessageId)
      : "";
    const animation = animationKey ? subagentTextAnimationsRef.current.get(animationKey) : null;
    const transcriptPayload = isTextDelta
      ? { ...payload, data: { ...payload.data, delta: "" } }
      : isResult
        ? { ...payload, data: { ...payload.data, content: "" } }
        : payload;

    setSubagentMessagesBySession((current) => ({
      ...current,
      [childSessionId]: applyHarnessTranscriptEvent(
        current[childSessionId] ?? [],
        transcriptPayload,
      ),
    }));
    if (isTextDelta) {
      const delta = typeof payload.data.delta === "string" ? payload.data.delta : "";
      enqueueSubagentText(childSessionId, animationMessageId, delta, true);
    } else if (isResult && !animation?.receivedDelta) {
      const content = typeof payload.data.content === "string" ? payload.data.content : "";
      enqueueSubagentText(childSessionId, animationMessageId, content, false);
    }
    const streamingState = harnessTranscriptStreamingState(payload);
    if (streamingState !== null) {
      setSubagentStreamingBySession((current) => ({
        ...current,
        [childSessionId]: streamingState,
      }));
    }
    if (payload.eventType === "AGENT_END" && animationMessageId) {
      void waitForSubagentText(childSessionId, animationMessageId).then(() => {
        subagentTextAnimationsRef.current.delete(animationKey);
      });
    }
  }
  applyHarnessRuntimeEventRef.current = applyHarnessRuntimeEvent;

  function subagentAnimationKey(childSessionId: string, messageId: string) {
    return `${childSessionId}:${messageId}`;
  }

  function enqueueSubagentText(
    childSessionId: string,
    messageId: string,
    content: string,
    receivedDelta: boolean,
  ) {
    if (!content) return;
    const key = subagentAnimationKey(childSessionId, messageId);
    let animation = subagentTextAnimationsRef.current.get(key);
    if (!animation) {
      animation = { queue: [], timer: null, receivedDelta: false, waiters: [] };
      subagentTextAnimationsRef.current.set(key, animation);
    }
    animation.receivedDelta ||= receivedDelta;
    const characters = Array.from(content);
    for (let index = 0; index < characters.length; index += 24) {
      animation.queue.push(characters.slice(index, index + 24).join(""));
    }
    if (animation.timer !== null) return;

    const pump = () => {
      const current = subagentTextAnimationsRef.current.get(key);
      if (!current) return;
      const chunk = current.queue.shift();
      if (chunk) {
        setSubagentMessagesBySession((messagesBySession) => ({
          ...messagesBySession,
          [childSessionId]: applyAssistantChunk(
            messagesBySession[childSessionId] ?? [],
            messageId,
            chunk,
          ),
        }));
      }
      if (current.queue.length > 0) {
        current.timer = setTimeout(pump, 24);
        return;
      }
      current.timer = null;
      const waiters = current.waiters.splice(0);
      waiters.forEach((resolve) => resolve());
    };
    animation.timer = setTimeout(pump, 0);
  }

  function waitForSubagentText(childSessionId: string, messageId: string) {
    const animation = subagentTextAnimationsRef.current.get(
      subagentAnimationKey(childSessionId, messageId),
    );
    if (!animation || (animation.timer === null && animation.queue.length === 0)) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => animation.waiters.push(resolve));
  }

  function hasPendingSubagentText(childSessionId: string) {
    const prefix = `${childSessionId}:`;
    return [...subagentTextAnimationsRef.current.entries()].some(([key, animation]) => (
      key.startsWith(prefix) && (animation.timer !== null || animation.queue.length > 0)
    ));
  }

  async function handleLoadOlderSubagentMessages() {
    if (!sessionId || !activeSubagent || loadingSubagentMessages || !hasOlderSubagentMessages) return;
    const cursor = nextSubagentBeforeSeq;
    if (!cursor) return;
    const childSessionId = activeSubagent.sessionId;
    setSubagentPaginationBySession((current) => ({
      ...current,
      [childSessionId]: {
        loading: true,
        hasMore: current[childSessionId]?.hasMore ?? false,
        nextBeforeSeq: current[childSessionId]?.nextBeforeSeq ?? cursor,
      },
    }));
    try {
      const page = await getChatSessionMessages(activeSubagent.sessionId, 20, cursor);
      setSubagentMessagesBySession((current) => ({
        ...current,
        [activeSubagent.sessionId]: [
          ...page.messages.map(toUiChatMessage),
          ...(current[activeSubagent.sessionId] ?? []),
        ],
      }));
      setSubagentPaginationBySession((current) => ({
        ...current,
        [childSessionId]: {
          loading: false,
          hasMore: page.hasMore,
          nextBeforeSeq: page.nextBeforeSeq,
        },
      }));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : "加载更多子对话失败");
    } finally {
      setSubagentPaginationBySession((current) => ({
        ...current,
        [childSessionId]: {
          loading: false,
          hasMore: current[childSessionId]?.hasMore ?? false,
          nextBeforeSeq: current[childSessionId]?.nextBeforeSeq ?? null,
        },
      }));
    }
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
    } catch (historyError) {
      setError(historyError instanceof Error ? historyError.message : "历史会话加载失败，请重试");
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
      router.replace(chatSessionHref(detail.session.sessionId), { scroll: false });
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
      router.replace(chatSessionHref(detail.session.sessionId), { scroll: false });
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "恢复会话失败");
    } finally {
      setResolvingChoice(false);
    }
  }

  async function handleCreateNewSession() {
    if (streaming) return;
    setDrawerOpen(false);
    setNewSessionPickerStep("types");
    setSelectedApprovalMode("DEFAULT");
    setDomainAgentSearch("");
    setSelectedAgentDomain("全部");
    setShowNewSessionPicker(true);
  }

  async function handleCreateNewSessionForAgent(
    targetAgentId: string,
    approvalMode?: ApprovalMode,
  ) {
    if (streaming) return;
    setCreatingNewAgentId(targetAgentId);
    try {
      const detail = await createChatSession(buildNewSessionPayload({
        currentSessionId: sessionId,
        targetAgentId,
        promptId: selectedPromptId,
        approvalMode,
      }));
      hydrateSession(detail, selectedPromptId);
      setShowNewSessionPicker(false);
      router.replace(chatSessionHref(detail.session.sessionId), { scroll: false });
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
      router.replace(chatSessionHref(detail.session.sessionId), { scroll: false });
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
      const container = historyContainerRef.current;
      pendingPrependScrollRef.current = container
        ? {
            previousScrollHeight: container.scrollHeight,
            previousScrollTop: container.scrollTop,
          }
        : null;
      setMessages((current) => [...olderMessages, ...current]);
      setHasOlderMessages(detail.hasMore);
      setNextBeforeSeq(detail.nextBeforeSeq);
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
          body: JSON.stringify(
            buildChatSendPayload({
                  message: content,
                  sessionId,
                  promptId: selectedPromptId,
                  agentId: currentAgentId,
                  resources: messageResources.length > 0 ? messageResources : undefined,
                }),
          ),
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
          onHarnessEvent(payload) {
            applyHarnessRuntimeEvent(payload);
          },
          onActionRequired(payload) {
            const request = payload as ApprovalRequest;
            setPendingApprovalBySession((current) => ({ ...current, [request.sessionId]: request }));
            setMessages((current) => removeEmptyAssistantPlaceholders(current));
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
      // Video generation creates a separate chat message asynchronously. Refresh once
      // after the stream so the local state sees that message even if its SSE event was missed.
      try {
        const latestPage = await getChatSessionMessages(sessionId, 100);
        setMessages(toUiChatMessages(latestPage.messages));
        if (usingHarnessAgent) {
          await refreshHarnessState(sessionId);
        }
      } catch {
        // The stream already completed; keep its successful result if this best-effort refresh fails.
      }
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

  async function handleSubagentSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const target = activeSubagent;
    const content = subagentInput.trim();
    if (!sessionId || !target || !content || !canSubmitSubagent) return;
    const childSessionId = target.sessionId;
    const resources = subagentPendingResources;
    const messageResources = resources.map((resource) => ({
      resourceId: resource.resourceId,
      role: resource.role,
      source: resource.source,
    }));
    const pendingMessageResources: ChatMessageResource[] = resources.map((resource) => ({
      id: resource.resourceId,
      type: resource.type,
      role: resource.role,
      viewUrl: resource.viewUrl,
      downloadUrl: resource.downloadUrl,
      fileName: resource.fileName,
      mimeType: resource.mimeType,
      fileSize: resource.fileSize,
      width: null,
      height: null,
    }));
    const seed = Date.now();
    const { userMessage, reasoningMessage, assistantMessage } = buildPendingAssistantTurn(
      content,
      seed,
      pendingMessageResources,
    );

    setSubagentInputBySession((current) => ({ ...current, [childSessionId]: "" }));
    setSubagentPendingResourcesBySession((current) => ({ ...current, [childSessionId]: [] }));
    setSubagentErrorBySession((current) => ({ ...current, [childSessionId]: "" }));
    setSubagentStreamingBySession((current) => ({ ...current, [childSessionId]: true }));
    setSubagentMessagesBySession((current) => ({
      ...current,
      [childSessionId]: [
        ...(current[childSessionId] ?? []),
        userMessage,
        reasoningMessage,
        assistantMessage,
      ],
    }));
    setHarnessUiState((current) => ({
      ...current,
      subagentsBySession: {
        ...current.subagentsBySession,
        [childSessionId]: { ...target, status: "RUNNING" },
      },
    }));
    const updateChild = (update: (messages: UiChatMessage[]) => UiChatMessage[]) => {
      setSubagentMessagesBySession((current) => ({
        ...current,
        [childSessionId]: update(current[childSessionId] ?? []),
      }));
    };
    let receivedDirectChunk = false;
    directSubagentStreamsRef.current.add(childSessionId);

    try {
      await apiStream(
        "/api/chat/messages/stream",
        {
          method: "POST",
          body: JSON.stringify(buildSubagentMessageRequest({
            message: content,
            sessionId: target.sessionId,
            agentId: currentAgentId,
            resources: messageResources.length > 0 ? messageResources : undefined,
          })),
        },
        {
          onUserMessage(message) {
            updateChild((current) => applyPersistedMessage(current, userMessage.id, message));
          },
          onReasoning(chunk) {
            updateChild((current) => applyReasoningChunk(current, reasoningMessage.id, chunk));
          },
          onChunk(chunk) {
            receivedDirectChunk = true;
            enqueueSubagentText(childSessionId, assistantMessage.id, chunk, true);
          },
          onBlocked(message) {
            updateChild((current) => applyBlockedState(current, assistantMessage.id, message));
          },
          onImage(message) {
            updateChild((current) => applyImageMessage(current, assistantMessage.id, message));
          },
          onAgentStep(step) {
            updateChild((current) => applyAgentStep(current, assistantMessage.id, step));
          },
          onHarnessEvent(payload) {
            applyHarnessRuntimeEvent(payload);
          },
          onActionRequired(payload) {
            const request = payload as ApprovalRequest;
            setPendingApprovalBySession((current) => ({ ...current, [request.sessionId]: request }));
            updateChild((current) => removeEmptyAssistantPlaceholders(current));
          },
          onDone(_content, message) {
            if (!receivedDirectChunk && message?.content) {
              enqueueSubagentText(childSessionId, assistantMessage.id, message.content, false);
            }
            void waitForSubagentText(childSessionId, assistantMessage.id).then(() => {
              updateChild((current) => removeEmptyAssistantPlaceholders(
                message ? applyPersistedMessage(current, assistantMessage.id, message) : current,
              ));
            });
          },
          onError(message) {
            setSubagentErrorBySession((current) => ({ ...current, [childSessionId]: message }));
          },
        },
      );
      await waitForSubagentText(childSessionId, assistantMessage.id);
      subagentTextAnimationsRef.current.delete(
        subagentAnimationKey(childSessionId, assistantMessage.id),
      );
      const [latestPage] = await Promise.all([
        getChatSessionMessages(target.sessionId, 100),
        refreshHarnessState(sessionId),
      ]);
      setSubagentMessagesBySession((current) => ({
        ...current,
        [childSessionId]: toUiChatMessages(latestPage.messages),
      }));
    } catch (streamError) {
      const message = streamError instanceof Error ? streamError.message : "发送失败";
      setSubagentErrorBySession((current) => ({ ...current, [childSessionId]: message }));
      updateChild((current) => current.map((item) =>
        item.id === assistantMessage.id && !item.content
          ? { ...item, content: "暂时无法响应，请稍后重试。" }
          : item,
      ));
      void refreshHarnessState(sessionId).catch(() => undefined);
    } finally {
      directSubagentStreamsRef.current.delete(childSessionId);
      setSubagentStreamingBySession((current) => ({ ...current, [childSessionId]: false }));
    }
  }

  async function handleApprovalDecision(request: ApprovalRequest, decision: ApprovalDecision) {
    if (decidingApprovalId || request.status !== "PENDING") return;
    const child = request.sessionId !== sessionId;
    const seed = Date.now();
    const reasoningId = `approval-reasoning-${seed}`;
    const assistantId = `approval-assistant-${seed}`;
    const reasoningMessage: UiChatMessage = {
      id: reasoningId, role: "assistant", messageType: "REASONING", content: "",
    };
    const assistantMessage: UiChatMessage = {
      id: assistantId, role: "assistant", messageType: "AI", content: "", agentSteps: [],
    };
    const update = (mapper: (items: UiChatMessage[]) => UiChatMessage[]) => {
      if (child) {
        setSubagentMessagesBySession((current) => ({
          ...current,
          [request.sessionId]: mapper(current[request.sessionId] ?? []),
        }));
      } else {
        setMessages(mapper);
      }
    };
    setDecidingApprovalId(request.approvalId);
    if (child) {
      setSubagentStreamingBySession((current) => ({ ...current, [request.sessionId]: true }));
    } else {
      setStreaming(true);
    }
    update((current) => [...current, reasoningMessage, assistantMessage]);
    try {
      await apiStream(
        approvalDecisionPath(request.approvalId),
        { method: "POST", body: JSON.stringify({ decision }) },
        {
          onReasoning(chunk) {
            update((current) => applyReasoningChunk(current, reasoningId, chunk));
          },
          onChunk(chunk) {
            update((current) => applyAssistantChunk(current, assistantId, chunk));
          },
          onBlocked(message) {
            update((current) => applyBlockedState(current, assistantId, message));
          },
          onImage(message) {
            update((current) => applyImageMessage(current, assistantId, message));
          },
          onAgentStep(step) {
            update((current) => applyAgentStep(current, assistantId, step));
          },
          onHarnessEvent(payload) {
            applyHarnessRuntimeEvent(payload);
          },
          onActionRequired(payload) {
            const next = payload as ApprovalRequest;
            setPendingApprovalBySession((current) => ({ ...current, [next.sessionId]: next }));
            update((current) => removeEmptyAssistantPlaceholders(current));
          },
          onDone(_content, message) {
            setPendingApprovalBySession((current) => ({ ...current, [request.sessionId]: null }));
            update((current) => removeEmptyAssistantPlaceholders(
              message ? applyPersistedMessage(current, assistantId, message) : current,
            ));
          },
          onError(message) {
            if (child) {
              setSubagentErrorBySession((current) => ({ ...current, [request.sessionId]: message }));
            } else {
              setError(message);
            }
          },
        },
      );
      const page = await getChatSessionMessages(request.sessionId, 100);
      if (child) {
        setSubagentMessagesBySession((current) => ({
          ...current,
          [request.sessionId]: toUiChatMessages(page.messages),
        }));
      } else {
        setMessages(toUiChatMessages(page.messages));
      }
      if (sessionId) await refreshHarnessState(sessionId);
    } catch (decisionError) {
      const message = decisionError instanceof Error ? decisionError.message : "审批处理失败";
      if (child) {
        setSubagentErrorBySession((current) => ({ ...current, [request.sessionId]: message }));
      } else {
        setError(message);
      }
      const latest = await getPendingApproval(request.sessionId).catch(() => request);
      setPendingApprovalBySession((current) => ({ ...current, [request.sessionId]: latest }));
      update((current) => removeEmptyAssistantPlaceholders(current));
    } finally {
      setDecidingApprovalId(null);
      if (child) {
        setSubagentStreamingBySession((current) => ({ ...current, [request.sessionId]: false }));
      } else {
        setStreaming(false);
      }
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
    <main className="h-dvh overflow-hidden bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] text-stone-900">
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
                <h2 className="mt-1 text-xl font-semibold">
                  {newSessionPickerStep === "types"
                    ? "选择新会话"
                    : newSessionPickerStep === "approval"
                      ? "选择批准模式"
                      : "选择领域 Agent"}
                </h2>
              </div>
              <div className="flex items-center gap-2">
                {newSessionPickerStep !== "types" ? (
                  <button
                    className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-600"
                    type="button"
                    disabled={creatingNewAgentId !== null}
                    onClick={() => setNewSessionPickerStep("types")}
                  >
                    返回
                  </button>
                ) : null}
                <button
                  className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-600"
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  onClick={() => setShowNewSessionPicker(false)}
                >
                  关闭
                </button>
              </div>
            </div>

            {newSessionPickerStep === "types" ? (
              <div className="mt-5 max-h-[70vh] space-y-3 overflow-y-auto pr-1">
                <button
                  className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left transition hover:border-amber-400 disabled:opacity-60"
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  onClick={() => handleCreateNewSessionForAgent(STANDARD_AGENT_ID)}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-stone-900">通用助手</p>
                      <p className="mt-1 text-xs leading-5 text-stone-500">自由对话，使用提示词、知识库和常用工具</p>
                    </div>
                    {creatingNewAgentId === STANDARD_AGENT_ID ? <span className="shrink-0 text-xs text-amber-700">创建中...</span> : null}
                  </div>
                </button>

                <button
                  className="w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-left transition hover:border-amber-400 disabled:opacity-60"
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  onClick={() => setNewSessionPickerStep("domain")}
                >
                  <p className="text-sm font-semibold text-stone-900">领域 Agent</p>
                  <p className="mt-1 text-xs leading-5 text-stone-500">选择专业 Agent 处理特定领域问题</p>
                </button>

                <button
                  className="w-full rounded-2xl border border-amber-300 bg-amber-50/80 px-4 py-3 text-left transition hover:border-amber-500 disabled:opacity-60"
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  onClick={() => setNewSessionPickerStep("approval")}
                >
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="text-sm font-semibold text-stone-900">协作 Agent</p>
                      <p className="mt-1 text-xs leading-5 text-stone-500">拆分复杂任务，组织多个 Agent 并行处理</p>
                    </div>
                    <span className="shrink-0 text-xs text-amber-700">继续 →</span>
                  </div>
                </button>
              </div>
            ) : newSessionPickerStep === "approval" ? (
              <div className="mt-5 max-h-[70vh] space-y-3 overflow-y-auto pr-1">
                <p className="text-xs leading-5 text-stone-500">批准模式创建后固定在该会话及其子 Agent 上，避免运行途中安全语义漂移。</p>
                {approvalModeOptions.map((mode) => (
                  <button
                    key={mode.value}
                    type="button"
                    disabled={creatingNewAgentId !== null}
                    className={`w-full rounded-2xl border px-4 py-3 text-left transition disabled:opacity-60 ${
                      selectedApprovalMode === mode.value
                        ? "border-stone-900 bg-stone-900 text-white"
                        : mode.tone === "open"
                          ? "border-red-200 bg-red-50 text-stone-800 hover:border-red-400"
                          : "border-stone-200 bg-white text-stone-800 hover:border-amber-400"
                    }`}
                    onClick={() => setSelectedApprovalMode(mode.value)}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-sm font-semibold">{mode.label}</p>
                        <p className={`mt-1 text-xs leading-5 ${selectedApprovalMode === mode.value ? "text-stone-300" : "text-stone-500"}`}>
                          {mode.description}
                        </p>
                      </div>
                      <span className="text-xs">{selectedApprovalMode === mode.value ? "已选择" : ""}</span>
                    </div>
                  </button>
                ))}
                <button
                  type="button"
                  disabled={creatingNewAgentId !== null}
                  className="h-12 w-full rounded-full bg-amber-600 text-sm font-semibold text-white disabled:opacity-50"
                  onClick={() => handleCreateNewSessionForAgent(HARNESS_AGENT_ID, selectedApprovalMode)}
                >
                  {creatingNewAgentId === HARNESS_AGENT_ID ? "创建中..." : "以此模式开始聊天"}
                </button>
              </div>
            ) : (
              <div className="mt-5">
                <input
                  className="h-11 w-full rounded-xl border border-stone-200 bg-white px-3 text-sm outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                  value={domainAgentSearch}
                  onChange={(event) => setDomainAgentSearch(event.target.value)}
                  placeholder="搜索名称、领域、说明或标签"
                  autoFocus
                />
                <div className="mt-3 flex gap-2 overflow-x-auto pb-1">
                  {agentDomains.map((domain) => (
                    <button
                      key={domain}
                      className={`shrink-0 rounded-full border px-3 py-1.5 text-xs font-medium ${
                        selectedAgentDomain === domain
                          ? "border-stone-900 bg-stone-900 text-white"
                          : "border-stone-200 bg-white text-stone-600"
                      }`}
                      type="button"
                      onClick={() => setSelectedAgentDomain(domain)}
                    >
                      {domain}
                    </button>
                  ))}
                </div>
                <div className="mt-3 max-h-[52vh] space-y-3 overflow-y-auto pr-1">
                  {filteredDomainAgents.map((agent) => (
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
                          <p className="mt-1 text-xs text-amber-700">{agent.domain}</p>
                          <p className="mt-1 line-clamp-2 text-xs leading-5 text-stone-500">{agent.summary}</p>
                        </div>
                        {creatingNewAgentId === agent.agentId ? (
                          <span className="shrink-0 text-xs text-amber-700">创建中...</span>
                        ) : null}
                      </div>
                    </button>
                  ))}
                  {filteredDomainAgents.length === 0 ? (
                    <p className="rounded-2xl border border-dashed border-stone-300 px-4 py-6 text-center text-sm text-stone-500">
                      没有匹配的领域 Agent
                    </p>
                  ) : null}
                </div>
              </div>
            )}
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
          <p className="text-xs uppercase tracking-[0.28em] text-amber-700">harness-agent</p>
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

      <section className="mx-auto flex h-dvh w-full max-w-md flex-col overflow-hidden">
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
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">
                H-Agent Chat
              </p>
              <h1 className="mt-2 truncate text-xl font-semibold">
                {currentSessionTitle}
              </h1>
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

        <div ref={historyContainerRef} className="min-h-0 flex-1 overflow-y-auto px-4 pb-36 pt-5">
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
          ) : usingHarnessAgent ? (
            <div className="sticky top-0 z-[5] -mx-4 -mt-5 bg-[#f7f4ea]/95 px-4 pb-1 pt-5 backdrop-blur">
              <div className="rounded-[1.5rem] border border-amber-200 bg-amber-50/90 p-4 shadow-sm">
                <div className="space-y-3">
                    <div className="flex items-center justify-between gap-3">
                      <div className="min-w-0">
                        <p className="text-xs uppercase tracking-[0.2em] text-amber-700">协作进度</p>
                        <p className="mt-1 truncate text-sm font-semibold text-stone-800">{currentAgentName}</p>
                      </div>
                      <div className="shrink-0 text-right text-xs text-stone-500">
                        <p>{harnessSubagents.length} 位协作者</p>
                        {currentApprovalMode ? (
                          <p className="mt-1 text-amber-700">
                            {approvalModeOptions.find((item) => item.value === currentApprovalMode)?.label}
                          </p>
                        ) : null}
                      </div>
                    </div>
                    {harnessSubagents.length > 0 && sessionId ? (
                      <div className="flex gap-3 overflow-x-auto pb-1">
                        {harnessSubagents.map((subagent) => (
                          <button
                            key={subagent.sessionId}
                            type="button"
                            className="flex w-20 shrink-0 flex-col items-center gap-1.5 rounded-2xl px-1 py-2 text-center transition hover:bg-white/70"
                            onClick={() => {
                              setSubagentPaginationBySession((current) => ({
                                ...current,
                                [subagent.sessionId]: {
                                  loading: true,
                                  hasMore: current[subagent.sessionId]?.hasMore ?? false,
                                  nextBeforeSeq: current[subagent.sessionId]?.nextBeforeSeq ?? null,
                                },
                              }));
                              setActiveSubagentSessionId(subagent.sessionId);
                            }}
                          >
                            <span className="relative flex h-11 w-11 items-center justify-center rounded-full bg-stone-900 text-sm font-semibold text-white">
                              {subagent.displayName.trim().slice(0, 1) || "协"}
                              <span className={`absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-amber-50 ${harnessStatusTone(subagent.status)}`} />
                            </span>
                            <span className="w-full truncate text-xs font-medium text-stone-700">{subagent.displayName}</span>
                            <span className="text-[10px] text-stone-500">{harnessStatusLabel[subagent.status]}</span>
                          </button>
                        ))}
                      </div>
                    ) : (
                      <p className="text-xs leading-5 text-stone-500">父 Agent 拆分任务后，协作者会出现在这里。</p>
                    )}
                </div>
              </div>
            </div>
          ) : (
            <div className="sticky top-0 z-[5] -mx-4 -mt-5 bg-[#f7f4ea]/95 px-4 pb-1 pt-5 backdrop-blur">
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
                      : turn.kind === "system"
                        ? "w-full max-w-full border border-amber-200 bg-amber-50 text-stone-700"
                      : turn.kind === "blocked"
                        ? "rounded-bl-md border border-amber-200 bg-amber-50/95 text-amber-900"
                        : turn.kind === "image"
                          ? "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700"
                        : "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700",
                  ].join(" ")}
                >
                  {turn.kind === "system" ? (
                    <div>
                      <p className="text-xs uppercase tracking-[0.18em] text-amber-700">系统消息</p>
                      <p className="mt-2 whitespace-pre-wrap">{turn.content}</p>
                    </div>
                  ) : turn.kind === "user" ? (
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
                      <PendingAssistantStatus steps={turn.agentSteps} streaming={streaming} />
                      <ReasoningDetails content={turn.reasoning} pending />
                    </div>
                  ) : (
                    <div className="space-y-3">
                      <AgentStepDetails steps={turn.agentSteps} pending />
                      <PendingAssistantStatus steps={turn.agentSteps} streaming={streaming} />
                    </div>
                  )}
                </div>
              </article>
            ))}
            {rootPendingApproval ? (
              <ApprovalCard
                request={rootPendingApproval}
                deciding={decidingApprovalId === rootPendingApproval.approvalId}
                onDecision={(decision) => void handleApprovalDecision(rootPendingApproval, decision)}
              />
            ) : null}
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
                const result = await uploadChatResource(file, "ATTACHMENT");
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
                  placeholder={usingStandardAgent
                      ? "输入你想聊的内容..."
                      : `询问 ${currentAgentName}...`}
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

        {activeSubagent ? (
          <div className="fixed inset-0 z-40 flex justify-end bg-stone-950/25 backdrop-blur-[1px]">
            <button
              type="button"
              className="absolute inset-0 cursor-default"
              aria-label="关闭子 Agent 抽屉"
              onClick={() => setActiveSubagentSessionId(null)}
            />
            <aside className="relative z-10 flex h-full w-[92%] max-w-md flex-col border-l border-stone-200 bg-[#f7f4ea] shadow-2xl">
              <header className="border-b border-stone-200 bg-[#f7f4ea]/95 px-4 pb-4 pt-[max(1rem,env(safe-area-inset-top))] backdrop-blur">
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-stone-300 bg-white text-xl"
                    aria-label="返回父 Agent"
                    onClick={() => setActiveSubagentSessionId(null)}
                  >
                    ←
                  </button>
                  <div className="min-w-0 flex-1">
                    <p className="text-xs uppercase tracking-[0.22em] text-amber-700">协作 Agent · 子对话</p>
                    <h2 className="mt-1 truncate text-lg font-semibold text-stone-900">{activeSubagent.displayName}</h2>
                  </div>
                  <span className="inline-flex items-center gap-1.5 text-xs text-stone-600">
                    <span className={`h-2 w-2 rounded-full ${harnessStatusTone(activeSubagent.status)}`} />
                    {harnessStatusLabel[activeSubagent.status]}
                  </span>
                </div>
              </header>

              <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-40 pt-4">
                {hasOlderSubagentMessages ? (
                  <div className="text-center">
                    <button
                      type="button"
                      className="rounded-full border border-stone-200 bg-white px-4 py-2 text-sm text-stone-600"
                      disabled={loadingSubagentMessages}
                      onClick={() => void handleLoadOlderSubagentMessages()}
                    >
                      {loadingSubagentMessages ? "加载中..." : "加载更早消息"}
                    </button>
                  </div>
                ) : null}
                <div className="mt-4 space-y-4">
                  {!loadingSubagentMessages && subagentMessages.length === 0 ? (
                    <article className="flex justify-start">
                      <div className="max-w-[88%] rounded-2xl rounded-bl-md border border-stone-200 bg-white px-4 py-3 text-sm leading-6 text-stone-600">
                        这个协作 Agent 暂无消息。完成首轮委托后，你可以继续追加要求。
                      </div>
                    </article>
                  ) : null}
                  {toRenderableTurns(subagentMessages).filter(isVisibleSubagentTurn).map((turn) => (
                    <article key={turn.id} className={`flex ${turn.kind === "user" ? "justify-end" : "justify-start"}`}>
                      <div className={`max-w-[88%] rounded-2xl px-4 py-3 text-sm leading-6 shadow-sm ${
                        turn.kind === "user"
                          ? "rounded-br-md bg-stone-900 text-white"
                          : turn.kind === "system"
                            ? "w-full max-w-full border border-amber-200 bg-amber-50 text-stone-700"
                          : "rounded-bl-md border border-stone-200 bg-white text-stone-700"
                      }`}>
                        {turn.kind === "system" ? (
                          <div>
                            <p className="text-xs uppercase tracking-[0.18em] text-amber-700">父 Agent 的委托</p>
                            <p className="mt-2 whitespace-pre-wrap">{turn.content}</p>
                          </div>
                        ) : turn.kind === "user" ? (
                          <div className="space-y-2">
                            {turn.resources?.length ? <MediaContent content={turn.content} resources={turn.resources} /> : null}
                            {turn.content ? <p className="whitespace-pre-wrap">{turn.content}</p> : null}
                          </div>
                        ) : turn.kind === "image" ? (
                          <MediaContent content={turn.content} resources={turn.resources} />
                        ) : turn.kind === "blocked" ? (
                          <div className="space-y-2">
                            <AgentStepDetails steps={turn.agentSteps} />
                            {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                            <BlockedMessageContent content={turn.blocked} />
                          </div>
                        ) : turn.answer || turn.resources.length > 0 ? (
                          <div className="space-y-2">
                            <AgentStepDetails steps={turn.agentSteps} />
                            {turn.reasoning ? <ReasoningDetails content={turn.reasoning} /> : null}
                            {turn.answer ? <AssistantMessageContent content={turn.answer} /> : null}
                            {turn.resources.length > 0 ? <MediaContent content={turn.answer} resources={turn.resources} /> : null}
                          </div>
                        ) : turn.reasoning ? (
                          <div className="space-y-2">
                            <AgentStepDetails steps={turn.agentSteps} pending />
                            <ReasoningDetails content={turn.reasoning} pending />
                          </div>
                        ) : (
                          <div className="space-y-2">
                            <AgentStepDetails steps={turn.agentSteps} pending />
                          </div>
                        )}
                      </div>
                    </article>
                  ))}
                  {subagentPendingApproval ? (
                    <ApprovalCard
                      request={subagentPendingApproval}
                      deciding={decidingApprovalId === subagentPendingApproval.approvalId}
                      onDecision={(decision) => void handleApprovalDecision(subagentPendingApproval, decision)}
                    />
                  ) : null}
                </div>
              </div>

              <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-[#f7f4ea] via-[#f7f4ea] to-transparent px-4 pb-[calc(1rem+env(safe-area-inset-bottom))] pt-8">
                <div className="rounded-[1.7rem] border border-stone-200 bg-[#f8f5ec]/95 p-3 shadow-lg backdrop-blur">
                  <input
                    ref={subagentFileInputRef}
                    type="file"
                    accept="image/jpeg,image/png,image/webp,video/mp4,audio/mpeg,audio/mp4,audio/wav,audio/webm"
                    className="hidden"
                    onChange={async (event) => {
                      const file = event.target.files?.[0];
                      if (!file || !sessionId) return;
                      if (file.size > 10 * 1024 * 1024) {
                        setSubagentErrorBySession((current) => ({
                          ...current,
                          [activeSubagent.sessionId]: "文件大小不能超过 10MB",
                        }));
                        return;
                      }
                      const childSessionId = activeSubagent.sessionId;
                      setSubagentUploadingBySession((current) => ({ ...current, [childSessionId]: true }));
                      setSubagentErrorBySession((current) => ({ ...current, [childSessionId]: "" }));
                      try {
                        const uploaded = await uploadChatResource(file, "ATTACHMENT");
                        setSubagentPendingResourcesBySession((current) => ({
                          ...current,
                          [childSessionId]: [...(current[childSessionId] ?? []), uploaded],
                        }));
                      } catch (uploadError) {
                        setSubagentErrorBySession((current) => ({
                          ...current,
                          [childSessionId]: uploadError instanceof Error ? uploadError.message : "上传失败",
                        }));
                      } finally {
                        setSubagentUploadingBySession((current) => ({ ...current, [childSessionId]: false }));
                        event.target.value = "";
                      }
                    }}
                  />
                  {subagentError ? <p className="px-2 pb-2 text-sm text-red-600">{subagentError}</p> : null}
                  {!canSubmitSubagentStatus(activeSubagent.status) ? (
                    <p className="px-2 pb-2 text-xs leading-5 text-stone-500">
                      {activeSubagent.status === "RUNNING"
                        ? "该协作者正在执行，完成后可继续追加。关闭抽屉不会中断运行。"
                        : "该协作者尚未完成首轮委托。"}
                    </p>
                  ) : null}
                  {subagentPendingResources.length > 0 ? (
                    <div className="flex gap-2 px-2 pb-2">
                      {subagentPendingResources.map((resource) => (
                        <div key={resource.resourceId} className="relative h-14 w-14">
                          <img
                            src={resource.viewUrl}
                            alt={resource.fileName}
                            className="h-full w-full rounded-lg border border-stone-200 object-cover"
                          />
                          <button
                            type="button"
                            className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-stone-800 text-xs text-white"
                            onClick={() => setSubagentPendingResourcesBySession((current) => ({
                              ...current,
                              [activeSubagent.sessionId]: (current[activeSubagent.sessionId] ?? [])
                                .filter((item) => item.resourceId !== resource.resourceId),
                            }))}
                          >
                            ×
                          </button>
                        </div>
                      ))}
                    </div>
                  ) : null}
                  <form className="flex items-end gap-3" onSubmit={handleSubagentSubmit}>
                    <button
                      type="button"
                      className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full border border-stone-200 bg-white text-stone-600 disabled:opacity-50"
                      onClick={() => subagentFileInputRef.current?.click()}
                      disabled={subagentUploading || subagentStreaming || !canSubmitSubagentStatus(activeSubagent.status)}
                    >
                      {subagentUploading ? "..." : "📎"}
                    </button>
                    <textarea
                      className="max-h-32 min-h-12 flex-1 resize-none rounded-[1.3rem] border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none focus:border-amber-500"
                      value={subagentInput}
                      onChange={(event) => setSubagentInputBySession((current) => ({
                        ...current,
                        [activeSubagent.sessionId]: event.target.value,
                      }))}
                      placeholder={`向 ${activeSubagent.displayName} 追加要求...`}
                      disabled={!canSubmitSubagentStatus(activeSubagent.status) || subagentStreaming}
                      rows={1}
                    />
                    <button
                      type="submit"
                      className="h-12 rounded-full bg-stone-900 px-5 text-sm font-semibold text-white disabled:bg-stone-400"
                      disabled={!canSubmitSubagent}
                    >
                      {subagentStreaming ? "生成中" : "发送"}
                    </button>
                  </form>
                </div>
              </div>
            </aside>
          </div>
        ) : null}
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

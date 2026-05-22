"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { apiStream } from "@/lib/http";
import { getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { SystemPrompt, listSystemPrompts } from "@/lib/system-prompts";
import {
  bootstrapChatSession,
  ChatSessionOpen,
  ChatSessionSummary,
  activateHistorySession,
  createChatSession,
  getChatSessionMessages,
  listChatHistory,
  resolveChatSession,
} from "@/lib/chat-sessions";

type ChatRole = "assistant" | "blocked" | "user";

type ChatMessage = {
  id: string;
  role: ChatRole;
  content: string;
};

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
          return (
            <p key={`text-${index}`} className="whitespace-pre-wrap">
              {segment.content}
            </p>
          );
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

export default function ChatPage() {
  const router = useRouter();
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const historyContainerRef = useRef<HTMLDivElement | null>(null);
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [bootstrapping, setBootstrapping] = useState(true);
  const [loadingOlderMessages, setLoadingOlderMessages] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [resolvingChoice, setResolvingChoice] = useState(false);
  const [error, setError] = useState("");
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState("新会话");
  const [showSessionChooser, setShowSessionChooser] = useState(false);
  const [sessionCandidates, setSessionCandidates] = useState<ChatSessionSummary[]>([]);
  const [historySessions, setHistorySessions] = useState<ChatSessionSummary[]>([]);
  const [historyPage, setHistoryPage] = useState(0);
  const [hasMoreHistory, setHasMoreHistory] = useState(true);
  const [historyLoadedForSession, setHistoryLoadedForSession] = useState<string | null>(null);
  const [hasOlderMessages, setHasOlderMessages] = useState(false);
  const [nextBeforeSeq, setNextBeforeSeq] = useState<number | null>(null);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        const [list, bootstrap] = await Promise.all([listSystemPrompts(), bootstrapChatSession()]);
        setAuthenticated(true);
        setPrompts(list);
        const defaultPrompt = list.find((prompt) => prompt.isDefault) ?? list[0] ?? null;
        if (bootstrap.resolution === "choose") {
          setSelectedPromptId(defaultPrompt?.id ?? null);
          setSessionCandidates(bootstrap.candidates);
          setShowSessionChooser(true);
          setMessages([]);
          return;
        }
        hydrateSession(bootstrap.session, defaultPrompt?.id ?? null);
      })
      .catch(() => {
        setAuthenticated(false);
        savePostLoginRedirect("/chat");
        router.replace("/auth/login");
      })
      .finally(() => setBootstrapping(false));
  }, [router]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const canSubmit = useMemo(
    () => input.trim().length > 0 && !streaming && !bootstrapping && !showSessionChooser && !!sessionId,
    [bootstrapping, input, sessionId, showSessionChooser, streaming],
  );

  function hydrateSession(open: ChatSessionOpen | null, fallbackPromptId: number | null) {
    if (!open) return;
    const detail = open.session;
    const messagePage = open.messagePage;
    setSessionId(detail.sessionId);
    setCurrentSessionTitle(detail.title || "新会话");
    setSelectedPromptId(detail.promptId ?? fallbackPromptId);
    setMessages(
      messagePage.messages.map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
      })),
    );
    setHasOlderMessages(messagePage.hasMore);
    setNextBeforeSeq(messagePage.nextBeforeSeq);
    setShowSessionChooser(false);
    setSessionCandidates([]);
    setHistoryLoadedForSession(null);
    setError("");
  }

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
    try {
      const detail = await createChatSession({
        currentSessionId: sessionId,
        promptId: selectedPromptId,
      });
      hydrateSession(detail, selectedPromptId);
    } catch (sessionError) {
      setError(sessionError instanceof Error ? sessionError.message : "新建会话失败");
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
      const olderMessages = detail.messages.map((message) => ({
        id: message.id,
        role: message.role,
        content: message.content,
      }));
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

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || streaming || !sessionId) return;

    const userMessage: ChatMessage = {
      id: `user-${Date.now()}`,
      role: "user",
      content,
    };
    const assistantId = `assistant-${Date.now()}`;

    setInput("");
    setError("");
    setStreaming(true);
    setMessages((current) => [
      ...current,
      userMessage,
      { id: assistantId, role: "assistant", content: "" },
    ]);

    try {
      await apiStream(
        "/api/chat/messages/stream",
        {
          method: "POST",
          body: JSON.stringify({
            message: content,
            sessionId,
            promptId: selectedPromptId,
          }),
        },
        {
          onChunk(chunk) {
            setMessages((current) =>
              current.map((message) =>
                message.id === assistantId
                  ? { ...message, content: `${message.content}${chunk}` }
                  : message,
              ),
            );
          },
          onBlocked(message) {
            setMessages((current) =>
              current.map((item) =>
                item.id === assistantId ? { ...item, role: "blocked", content: message } : item,
              ),
            );
          },
          onDone(finalContent) {
            setMessages((current) =>
              current.map((message) =>
                message.id === assistantId && message.role === "assistant"
                  ? { ...message, content: finalContent }
                  : message,
              ),
            );
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
          item.id === assistantId && !item.content
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
            <div>
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent Chat</p>
              <h1 className="mt-2 text-xl font-semibold">{currentSessionTitle}</h1>
            </div>
          </div>
        </header>

        <div ref={historyContainerRef} className="flex-1 overflow-y-auto px-4 pb-36 pt-5">
          <div className="rounded-[1.5rem] border border-stone-200 bg-white/90 p-4 shadow-sm">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-xs uppercase tracking-[0.2em] text-amber-700">SystemPrompt</p>
                <p className="mt-1 text-sm text-stone-500">选择当前对话使用的系统提示词</p>
              </div>
              <Link className="text-sm font-medium text-amber-700" href="/me/system-prompts">
                管理
              </Link>
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
            {messages.map((message) => (
              <article
                key={message.id}
                className={`flex ${message.role === "user" ? "justify-end" : "justify-start"}`}
              >
                <div
                  className={[
                    "max-w-[85%] rounded-[1.5rem] px-4 py-3 text-sm leading-6 shadow-sm",
                    message.role === "user"
                      ? "rounded-br-md bg-stone-900 text-stone-50"
                      : message.role === "blocked"
                        ? "rounded-bl-md border border-amber-200 bg-amber-50/95 text-amber-900"
                        : "rounded-bl-md border border-stone-200 bg-white/95 text-stone-700",
                  ].join(" ")}
                >
                  {message.role === "assistant" ? (
                    message.content ? (
                      <AssistantMessageContent content={message.content} />
                    ) : streaming ? (
                      "正在思考..."
                    ) : (
                      ""
                    )
                  ) : message.role === "blocked" ? (
                    <BlockedMessageContent content={message.content} />
                  ) : (
                    message.content
                  )}
                </div>
              </article>
            ))}
            <div ref={messageEndRef} />
          </div>
        </div>

        <div className="fixed bottom-0 left-0 right-0 mx-auto w-full max-w-md bg-transparent px-4 pb-[calc(1rem+env(safe-area-inset-bottom))]">
          <div className="rounded-[2rem] border border-stone-200 bg-[#f8f5ec]/95 p-3 shadow-[0_-8px_30px_rgba(58,45,28,0.12)] backdrop-blur">
            {error ? <p className="px-2 pb-2 text-sm text-red-600">{error}</p> : null}

            <form className="flex items-end gap-3" onSubmit={handleSubmit}>
              <textarea
                className="max-h-32 min-h-12 flex-1 resize-none rounded-[1.4rem] border border-stone-200 bg-white px-4 py-3 text-sm leading-6 outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder="输入你想聊的内容..."
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
      </section>
    </main>
  );
}

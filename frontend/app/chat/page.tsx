"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { apiStream } from "@/lib/http";
import { getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { SystemPrompt, listSystemPrompts } from "@/lib/system-prompts";

type ChatRole = "assistant" | "user";

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

export default function ChatPage() {
  const router = useRouter();
  const sessionIdRef = useRef(crypto.randomUUID());
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState("");
  const [prompts, setPrompts] = useState<SystemPrompt[]>([]);
  const [selectedPromptId, setSelectedPromptId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      id: "welcome",
      role: "assistant",
      content: "你好，我是 嘿 。登录后你可以在这里和我聊天了！",
    },
  ]);

  useEffect(() => {
    getCurrentUser()
      .then(async () => {
        setAuthenticated(true);
        const list = await listSystemPrompts();
        setPrompts(list);
        const defaultPrompt = list.find((prompt) => prompt.isDefault) ?? list[0] ?? null;
        setSelectedPromptId(defaultPrompt?.id ?? null);
      })
      .catch(() => {
        setAuthenticated(false);
        savePostLoginRedirect("/chat");
        router.replace("/auth/login");
      });
  }, [router]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const canSubmit = useMemo(() => input.trim().length > 0 && !streaming, [input, streaming]);

  function handleSelectPrompt(promptId: number) {
    if (promptId === selectedPromptId || streaming) return;
    setSelectedPromptId(promptId);
    sessionIdRef.current = crypto.randomUUID();
    setError("");
    setMessages([
      {
        id: `welcome-${promptId}`,
        role: "assistant",
        content: "已切换系统提示词，现在可以开始新的对话。",
      },
    ]);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const content = input.trim();
    if (!content || streaming) return;

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
            sessionId: sessionIdRef.current,
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
          onDone(finalContent) {
            setMessages((current) =>
              current.map((message) =>
                message.id === assistantId ? { ...message, content: finalContent } : message,
              ),
            );
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
              onClick={() => setDrawerOpen(true)}
            >
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
              <span className="h-0.5 w-5 rounded-full bg-stone-800" />
            </button>
            <div>
              <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent Chat</p>
              <h1 className="mt-2 text-xl font-semibold">AI 对话</h1>
            </div>
          </div>
        </header>

        <div className="flex-1 overflow-y-auto px-4 pb-36 pt-5">
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

            <div className="mt-3 rounded-[1.4rem] bg-white px-4 py-3 text-center text-sm font-medium text-stone-500">
              当前仅开放 AI 对话模块
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}

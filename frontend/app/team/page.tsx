"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { MatrixClient, MatrixEvent } from "matrix-js-sdk";
import { MarkdownContent } from "@/components/markdown-content";
import { apiFetch, ApiError } from "@/lib/http";
import styles from "./team-room.module.css";
import {
  buildTextContent, kindLabel, mentionIdsFor, mentionTrigger, senderLabel, teamMembers, toMessageView,
  type TeamConfig, type TeamMember, type TeamMessageView,
} from "@/lib/team-room";

type TeamMatrixSession = {
  homeserverUrl: string;
  roomId: string;
  managerUserId: string;
  workerUserId: string;
  accessToken: string;
  userId: string;
  deviceId: string;
};

export default function TeamRoomPage() {
  const [session, setSession] = useState<TeamMatrixSession | null>(null);
  const [config, setConfig] = useState<TeamConfig | null>(null);
  const [messages, setMessages] = useState<TeamMessageView[]>([]);
  const [roomMembers, setRoomMembers] = useState<string[]>([]);
  const [input, setInput] = useState("");
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const [status, setStatus] = useState("连接中…");
  const [error, setError] = useState("");
  const [needsLogin, setNeedsLogin] = useState(false);
  const [sending, setSending] = useState(false);
  const clientRef = useRef<MatrixClient | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<TeamMatrixSession>("/api/team/matrix-session")
      .then((data) => {
        if (cancelled || !data) return;
        setSession(data);
        setConfig({
          homeserverUrl: data.homeserverUrl,
          roomId: data.roomId,
          managerUserId: data.managerUserId,
          workerUserId: data.workerUserId,
        });
        setRoomMembers([data.userId, data.managerUserId, data.workerUserId]);
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.code === 40100) {
          setNeedsLogin(true);
        } else {
          setError(e instanceof Error ? e.message : "无法接入团队聊天室");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const appendEvent = useCallback((event: MatrixEvent) => {
    const view = toMessageView(event.event as never);
    if (!view) return;
    setMessages((current) => current.some((m) => m.id === view.id) ? current : [...current, view]);
  }, []);

  useEffect(() => {
    if (!session || !config || !config.roomId) return;
    let cancelled = false;
    let client: MatrixClient | null = null;
    // SDK 只在浏览器加载：顶层 import 会在 SSR 重复求值触发其入口保护。
    import("matrix-js-sdk").then((sdk) => {
      if (cancelled) return;
      const c = sdk.createClient({
        baseUrl: config.homeserverUrl,
        accessToken: session.accessToken,
        userId: session.userId,
        deviceId: session.deviceId,
      });
      client = c;
      clientRef.current = c;
      const loadTimeline = () => {
        const room = c.getRoom(config.roomId);
        if (!room) return;
        const views = room.timeline
          .map((event) => toMessageView(event.event as never))
          .filter((view): view is TeamMessageView => view !== null);
        setMessages(views);
        setRoomMembers(room.getJoinedMembers().map((member) => member.userId));
        setStatus("已连接");
      };
      c.on("sync" as never, ((state: string) => {
        if (state === "PREPARED" || state === "SYNCING") loadTimeline();
      }) as never);
      c.on("Room.timeline" as never, ((event: MatrixEvent, room: { roomId: string } | undefined, toStart: boolean) => {
        if (room?.roomId === config.roomId && !toStart) appendEvent(event);
      }) as never);
      c.startClient({ initialSyncLimit: 100 }).catch((e: Error) => setError(`同步失败：${e.message}`));
    }).catch((e: Error) => setError(`加载聊天组件失败：${e.message}`));
    return () => {
      cancelled = true;
      if (client) {
        client.removeAllListeners();
        client.stopClient();
      }
      clientRef.current = null;
    };
  }, [session, config, appendEvent]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const client = clientRef.current;
    const text = input.trim();
    if (!client || !config || !text || sending) return;
    setSending(true);
    setError("");
    setMention(null);
    try {
      await client.sendEvent(config.roomId, "m.room.message" as never, buildTextContent(text, mentionIdsFor(text, config)) as never, "");
      setInput("");
    } catch (e) {
      setError(e instanceof Error ? `发送失败：${e.message}` : "发送失败");
    } finally {
      setSending(false);
    }
  }

  const members = config ? teamMembers(config) : [];
  const mentionMatches = mention
    ? members.filter((member) => member.label.toLowerCase().startsWith(mention.query.toLowerCase()))
    : [];

  function selectMember(member: TeamMember) {
    const textarea = inputRef.current;
    if (!textarea || !mention) return;
    const caret = textarea.selectionStart ?? input.length;
    const inserted = `@${member.label} `;
    const next = input.slice(0, mention.start) + inserted + input.slice(caret);
    const pos = mention.start + inserted.length;
    setInput(next);
    setMention(null);
    requestAnimationFrame(() => {
      textarea.focus();
      textarea.setSelectionRange(pos, pos);
    });
  }

  if (needsLogin) {
    return (
      <main className="chat-workspace flex h-full flex-1 flex-col items-center justify-center gap-3 p-6 text-stone-900">
        <h1 className="text-base font-semibold">团队聊天室</h1>
        <p className="text-sm text-stone-500">登录工作台后会自动接入团队聊天室。</p>
        <Link href="/auth/login" className="agui-button agui-button-primary">去登录</Link>
      </main>
    );
  }

  if (!session || !config) {
    return (
      <main className="chat-workspace flex h-full flex-1 flex-col items-center justify-center gap-3 p-6 text-stone-900">
        <h1 className="text-base font-semibold">团队聊天室</h1>
        <p className="text-sm text-stone-500">{error || "正在接入…"}</p>
      </main>
    );
  }

  const memberOrder = (userId: string) =>
    userId === session.userId ? 0 : userId === config.managerUserId ? 1 : userId === config.workerUserId ? 2 : 3;
  const sortedMembers = [...roomMembers].sort((a, b) => memberOrder(a) - memberOrder(b) || a.localeCompare(b));
  const memberRole = (userId: string) =>
    userId === session.userId ? "人类" : userId === config.managerUserId ? "协调" : userId === config.workerUserId ? "执行" : "成员";

  return (
    <main className="chat-workspace flex h-full min-h-0 w-full flex-col overflow-hidden text-stone-900">
      <header className="chat-header z-10 shrink-0 border-b border-stone-200 px-4 py-4 sm:px-8">
        <div className="mx-auto flex w-full items-center gap-3">
          <div className="min-w-0 flex-1">
            <h1 className="truncate font-semibold">团队聊天室</h1>
            <p className="mt-0.5 text-xs text-stone-500">{status} · 不点名默认由 Manager 接待，@Worker 可直接提问</p>
          </div>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 sm:px-8">
            <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
              {messages.map((message) => {
                const isSelf = message.sender === session.userId;
                if (isSelf) {
                  return (
                    <div key={message.id} className="flex justify-end">
                      <div className={styles.userBubble}>{message.body}</div>
                    </div>
                  );
                }
                const kind = kindLabel(message.kind);
                return (
                  <div key={message.id} className="flex flex-col gap-1.5">
                    <div className={styles.meta}>
                      <span className={styles.sender}>{senderLabel(message.sender, session.userId, config)}</span>
                      {kind && (
                        <span className={styles.kind}>
                          {kind}{message.taskId ? ` · ${message.taskId}` : ""}
                        </span>
                      )}
                      <span>{new Date(message.ts).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}</span>
                    </div>
                    <div className={styles.prose}>
                      <MarkdownContent content={message.body} />
                    </div>
                  </div>
                );
              })}
              <div ref={bottomRef} />
            </div>
          </div>
          <form onSubmit={send} className="shrink-0 px-4 pb-4 pt-2 sm:px-8">
            <div className="mx-auto w-full max-w-3xl">
              {error && <p className={styles.error}>{error}</p>}
              <div className={styles.composerWrap}>
                {mention && (
                  <div className={styles.mentionMenu} role="listbox" aria-label="选择成员">
                    {mentionMatches.length > 0 ? mentionMatches.map((member, index) => (
                      <button
                        key={member.userId}
                        type="button"
                        role="option"
                        aria-selected={index === mentionIndex}
                        className={`${styles.mentionItem} ${index === mentionIndex ? styles.mentionItemActive : ""}`}
                        onMouseDown={(e) => {
                          e.preventDefault();
                          selectMember(member);
                        }}
                        onMouseEnter={() => setMentionIndex(index)}
                      >
                        <span>@{member.label}</span>
                        <span className={styles.mentionDesc}>{member.description}</span>
                      </button>
                    )) : <p className={styles.mentionEmpty}>无匹配成员</p>}
                  </div>
                )}
                <div className={styles.composer}>
                  <textarea
                    ref={inputRef}
                    value={input}
                    onChange={(e) => {
                      const value = e.target.value;
                      setInput(value);
                      setMention(mentionTrigger(value, e.target.selectionStart ?? value.length));
                      setMentionIndex(0);
                    }}
                    onBlur={() => setMention(null)}
                    onKeyDown={(e) => {
                      if (e.nativeEvent.isComposing) return;
                      if (mention && mentionMatches.length > 0) {
                        if (e.key === "ArrowDown") {
                          e.preventDefault();
                          setMentionIndex((i) => (i + 1) % mentionMatches.length);
                          return;
                        }
                        if (e.key === "ArrowUp") {
                          e.preventDefault();
                          setMentionIndex((i) => (i - 1 + mentionMatches.length) % mentionMatches.length);
                          return;
                        }
                        if (e.key === "Enter" || e.key === "Tab") {
                          e.preventDefault();
                          selectMember(mentionMatches[mentionIndex] ?? mentionMatches[0]);
                          return;
                        }
                      }
                      if (e.key === "Escape" && mention) {
                        e.preventDefault();
                        setMention(null);
                        return;
                      }
                      if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        void send(e);
                      }
                    }}
                    placeholder="输入消息，@ 可选择成员"
                    rows={1}
                  />
                  <div className={styles.footer}>
                    <span className={styles.hint}>Enter 发送，Shift+Enter 换行</span>
                    <button
                      type="submit"
                      aria-label="发送"
                      disabled={sending || !input.trim()}
                      className={styles.send}
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5" /><path d="m5 12 7-7 7 7" /></svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </form>
        </div>
        <aside className={styles.members} aria-label="房间成员">
          <h2 className={styles.membersHeading}>房间成员<span>{sortedMembers.length}</span></h2>
          {sortedMembers.map((userId) => {
            const label = senderLabel(userId, session.userId, config);
            return (
              <div key={userId} className={styles.memberRow}>
                <span className={styles.avatar} aria-hidden="true">{label === "自己" ? "自己" : label.charAt(0).toUpperCase()}</span>
                <span className="min-w-0">
                  <span className={`${styles.memberName} block truncate`}>{label}</span>
                  <span className={`${styles.memberRole} block`}>{memberRole(userId)}</span>
                </span>
              </div>
            );
          })}
        </aside>
      </div>
    </main>
  );
}
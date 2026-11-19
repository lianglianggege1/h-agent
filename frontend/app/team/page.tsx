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
  springWorkerUserId: string;
  accessToken: string;
  userId: string;
  deviceId: string;
};

type DirectChat = { userId: string; roomId: string; label: string };

type RoomLike = {
  roomId: string;
  timeline: MatrixEvent[];
  getJoinedMembers(): { userId: string }[];
  getMembersWithMembership(membership: string): { userId: string }[];
};

function timelineViews(room: RoomLike): TeamMessageView[] {
  return room.timeline
    .map((event) => toMessageView(event.event as never))
    .filter((view): view is TeamMessageView => view !== null);
}

function findDmRoom(client: MatrixClient, selfUserId: string, targetUserId: string, teamRoomId: string): string | null {
  const direct = (client.getAccountData("m.direct" as never)?.getContent() ?? {}) as Record<string, unknown>;
  const known = Array.isArray(direct[targetUserId]) ? direct[targetUserId] as string[] : [];
  for (const roomId of known) {
    if (roomId !== teamRoomId && client.getRoom(roomId)) return roomId;
  }
  for (const room of client.getRooms() as unknown as RoomLike[]) {
    if (room.roomId === teamRoomId) continue;
    const userIds = new Set([
      ...room.getJoinedMembers().map((member) => member.userId),
      ...room.getMembersWithMembership("invite").map((member) => member.userId),
    ]);
    if (userIds.size === 2 && userIds.has(selfUserId) && userIds.has(targetUserId)) return room.roomId;
  }
  return null;
}

export default function TeamRoomPage() {
  const [session, setSession] = useState<TeamMatrixSession | null>(null);
  const [config, setConfig] = useState<TeamConfig | null>(null);
  const [messages, setMessages] = useState<TeamMessageView[]>([]);
  const [roomMembers, setRoomMembers] = useState<string[]>([]);
  const [dm, setDm] = useState<DirectChat | null>(null);
  const [dmRooms, setDmRooms] = useState<Record<string, string>>({});
  const [dmMessagesByRoom, setDmMessagesByRoom] = useState<Record<string, TeamMessageView[]>>({});
  const [dmJoined, setDmJoined] = useState(false);
  const [dmBusy, setDmBusy] = useState(false);
  const [input, setInput] = useState("");
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const [status, setStatus] = useState("连接中…");
  const [error, setError] = useState("");
  const [needsLogin, setNeedsLogin] = useState(false);
  const [sending, setSending] = useState(false);
  const clientRef = useRef<MatrixClient | null>(null);
  const dmRoomRef = useRef<string | null>(null);
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
          springWorkerUserId: data.springWorkerUserId,
        });
        setRoomMembers([data.userId, data.managerUserId, data.workerUserId, data.springWorkerUserId]);
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

  const appendDmEvent = useCallback((event: MatrixEvent, roomId: string) => {
    const view = toMessageView(event.event as never);
    if (!view) return;
    setDmMessagesByRoom((current) => {
      const list = current[roomId] ?? [];
      return list.some((m) => m.id === view.id) ? current : { ...current, [roomId]: [...list, view] };
    });
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
        setMessages(timelineViews(room as unknown as RoomLike));
        setRoomMembers(room.getJoinedMembers().map((member) => member.userId));
        setStatus("已连接");
      };
      const loadDmTimeline = (roomId: string) => {
        const room = c.getRoom(roomId);
        if (room) setDmMessagesByRoom((current) => ({ ...current, [roomId]: timelineViews(room as unknown as RoomLike) }));
      };
      const loadKnownDms = () => {
        for (const member of teamMembers(config)) {
          const roomId = findDmRoom(c, session.userId, member.userId, config.roomId);
          if (!roomId) continue;
          setDmRooms((current) => ({ ...current, [member.userId]: roomId }));
          loadDmTimeline(roomId);
        }
      };
      c.on("sync" as never, ((state: string) => {
        if (state === "PREPARED" || state === "SYNCING") loadTimeline();
        if (state === "PREPARED") {
          loadKnownDms();
          if (dmRoomRef.current) loadDmTimeline(dmRoomRef.current);
        }
      }) as never);
      c.on("Room.timeline" as never, ((event: MatrixEvent, room: { roomId: string } | undefined, toStart: boolean) => {
        if (toStart || !room) return;
        if (room.roomId === config.roomId) appendEvent(event);
        else appendDmEvent(event, room.roomId);
      }) as never);
      c.on("Room" as never, ((room: { roomId: string }) => {
        if (room.roomId !== config.roomId) loadDmTimeline(room.roomId);
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
  }, [session, config, appendEvent, appendDmEvent]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, dmMessagesByRoom, dm]);

  // 单聊房间创建后对方异步加入：轮询成员状态，加入时拉一次完整时间线。
  useEffect(() => {
    if (!dm || dmJoined) return;
    const timer = setInterval(() => {
      const room = clientRef.current?.getRoom(dm.roomId) as unknown as RoomLike | null;
      if (!room) return;
      if (room.getJoinedMembers().some((member) => member.userId === dm.userId)) {
        setDmJoined(true);
        setDmMessagesByRoom((current) => ({ ...current, [dm.roomId]: timelineViews(room) }));
      }
    }, 2000);
    return () => clearInterval(timer);
  }, [dm, dmJoined]);

  async function openDm(userId: string) {
    const client = clientRef.current;
    if (!client || !session || !config || dmBusy || userId === session.userId) return;
    const label = senderLabel(userId, session.userId, config);
    setDmBusy(true);
    setError("");
    try {
      let roomId = findDmRoom(client, session.userId, userId, config.roomId);
      if (!roomId) {
        const created = await client.createRoom({
          invite: [userId],
          preset: "trusted_private_chat",
          is_direct: true,
          name: `与 ${label} 的单聊`,
        } as never) as { room_id: string };
        roomId = created.room_id;
        const direct = (client.getAccountData("m.direct" as never)?.getContent() ?? {}) as Record<string, string[]>;
        await client.setAccountData("m.direct" as never, {
          ...direct,
          [userId]: [...(direct[userId] ?? []), roomId],
        } as never);
      }
      dmRoomRef.current = roomId;
      setDmRooms((current) => ({ ...current, [userId]: roomId }));
      const room = client.getRoom(roomId) as unknown as RoomLike | null;
      if (room) setDmMessagesByRoom((current) => ({ ...current, [roomId]: timelineViews(room) }));
      setDmJoined(Boolean(room?.getJoinedMembers().some((member) => member.userId === userId)));
      setDm({ userId, roomId, label });
      setMention(null);
    } catch (e) {
      setError(e instanceof Error ? `打开单聊失败：${e.message}` : "打开单聊失败");
    } finally {
      setDmBusy(false);
    }
  }

  function closeDm() {
    dmRoomRef.current = null;
    setDm(null);
    setDmJoined(false);
    setMention(null);
  }

  async function send(event: React.FormEvent) {
    event.preventDefault();
    const client = clientRef.current;
    const text = input.trim();
    if (!client || !config || !text || sending) return;
    const targetRoomId = dm ? dm.roomId : config.roomId;
    const mentionIds = dm ? [] : mentionIdsFor(text, config);
    setSending(true);
    setError("");
    setMention(null);
    try {
      await client.sendEvent(targetRoomId, "m.room.message" as never, buildTextContent(text, mentionIds) as never, "");
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
    userId === session.userId ? 0
      : userId === config.managerUserId ? 1
        : userId === config.workerUserId ? 2
          : userId === config.springWorkerUserId ? 3 : 4;
  const sortedMembers = [...roomMembers].sort((a, b) => memberOrder(a) - memberOrder(b) || a.localeCompare(b));
  const memberRole = (userId: string) =>
    userId === session.userId ? "人类"
      : userId === config.managerUserId ? "协调"
        : userId === config.workerUserId || userId === config.springWorkerUserId ? "执行" : "成员";

  const visibleMessages = dm ? (dmMessagesByRoom[dm.roomId] ?? []) : messages;

  const previewOf = (views: TeamMessageView[]) => {
    const last = views[views.length - 1];
    if (!last) return null;
    const who = last.sender === session.userId ? "自己" : senderLabel(last.sender, session.userId, config);
    return { text: `${who}：${last.body.replace(/\s+/g, " ")}`, ts: last.ts };
  };
  const conversations = [
    {
      key: "team", label: "团队聊天室", avatar: "群", active: !dm,
      preview: previewOf(messages), fallback: "群聊 · 点击成员头像可单聊",
      onClick: closeDm,
    },
    ...members.map((member) => {
      const roomId = dmRooms[member.userId];
      return {
        key: member.userId, label: member.label, avatar: member.label.charAt(0).toUpperCase(),
        active: dm?.userId === member.userId,
        preview: roomId ? previewOf(dmMessagesByRoom[roomId] ?? []) : null,
        fallback: member.description,
        onClick: () => void openDm(member.userId),
      };
    }),
  ];

  function renderMessage(message: TeamMessageView) {
    const isSelf = message.sender === session!.userId;
    const label = senderLabel(message.sender, session!.userId, config!);
    const avatarText = isSelf ? "自己" : label.charAt(0).toUpperCase();
    const time = new Date(message.ts).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
    if (isSelf) {
      return (
        <div key={message.id} className={`${styles.messageRow} ${styles.messageRowSelf}`}>
          <span className={styles.avatar} aria-hidden="true">{avatarText}</span>
          <div className={`${styles.messageBody} ${styles.messageBodySelf}`}>
            <div className={`${styles.meta} ${styles.metaSelf}`}><span className={styles.sender}>自己</span><span>{time}</span></div>
            <div className={styles.userBubble}>{message.body}</div>
          </div>
        </div>
      );
    }
    const kind = kindLabel(message.kind);
    return (
      <div key={message.id} className={styles.messageRow}>
        {dm ? (
          <span className={styles.avatar} aria-hidden="true">{avatarText}</span>
        ) : (
          <button type="button" className={styles.avatarBtn} title={`与 ${label} 单聊`} onClick={() => void openDm(message.sender)}>
            <span className={styles.avatar} aria-hidden="true">{avatarText}</span>
          </button>
        )}
        <div className={styles.messageBody}>
          <div className={styles.meta}>
            {dm ? (
              <span className={styles.sender}>{label}</span>
            ) : (
              <button type="button" className={styles.senderLink} title={`与 ${label} 单聊`} onClick={() => void openDm(message.sender)}>
                {label}
              </button>
            )}
            {kind && (
              <span className={styles.kind}>
                {kind}{message.taskId ? ` · ${message.taskId}` : ""}
              </span>
            )}
            <span>{time}</span>
          </div>
          <div className={styles.prose}>
            <MarkdownContent content={message.body} />
          </div>
        </div>
      </div>
    );
  }

  return (
    <main className="chat-workspace flex h-full min-h-0 w-full flex-col overflow-hidden text-stone-900">
      <header className="chat-header z-10 shrink-0 border-b border-stone-200 px-4 py-4 sm:px-8">
        <div className="mx-auto flex w-full items-center gap-3">
          {dm && (
            <button type="button" className={styles.dmBack} onClick={closeDm}>← 返回群聊</button>
          )}
          <div className="min-w-0 flex-1">
            <h1 className="truncate font-semibold">{dm ? `与 ${dm.label} 单聊` : "团队聊天室"}</h1>
            <p className="mt-0.5 text-xs text-stone-500">
              {dm
                ? `${memberRole(dm.userId)} · ${dmJoined ? "对方已加入" : "等待对方加入…（消息会在对方加入后送达）"}`
                : `${status} · 不点名默认由 Manager 接待，点击成员可单聊`}
            </p>
          </div>
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        <nav className={styles.convList} aria-label="消息列表">
          <h2 className={styles.convHeading}>消息</h2>
          {conversations.map((conv) => (
            <button
              key={conv.key}
              type="button"
              onClick={conv.onClick}
              disabled={dmBusy && conv.key !== "team"}
              className={`${styles.convItem} ${conv.active ? styles.convItemActive : ""}`}
            >
              <span className={styles.avatar} aria-hidden="true">{conv.avatar}</span>
              <span className="min-w-0 flex-1">
                <span className={styles.convTop}>
                  <span className={`${styles.convName} truncate`}>{conv.label}</span>
                  {conv.preview && (
                    <span className={styles.convTime}>
                      {new Date(conv.preview.ts).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
                    </span>
                  )}
                </span>
                <span className={`${styles.convPreview} block truncate`}>{conv.preview ? conv.preview.text : conv.fallback}</span>
              </span>
            </button>
          ))}
        </nav>
        <div className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1 overflow-y-auto px-4 py-6 sm:px-8">
            <div className="mx-auto flex w-full max-w-3xl flex-col gap-6">
              {visibleMessages.map(renderMessage)}
              <div ref={bottomRef} />
            </div>
          </div>
          <form onSubmit={send} className="shrink-0 px-4 pb-4 pt-2 sm:px-8">
            <div className="mx-auto w-full max-w-3xl">
              {error && <p className={styles.error}>{error}</p>}
              <div className={styles.composerWrap}>
                {mention && !dm && (
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
                      setMention(dm ? null : mentionTrigger(value, e.target.selectionStart ?? value.length));
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
                    placeholder={dm ? `与 ${dm.label} 单聊，Enter 发送` : "输入消息，@ 可选择成员"}
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
            const isSelf = userId === session.userId;
            const inner = (
              <>
                <span className={styles.avatar} aria-hidden="true">{isSelf ? "自己" : label.charAt(0).toUpperCase()}</span>
                <span className="min-w-0">
                  <span className={`${styles.memberName} block truncate`}>{label}</span>
                  <span className={`${styles.memberRole} block`}>{memberRole(userId)}</span>
                </span>
              </>
            );
            if (isSelf) {
              return <div key={userId} className={styles.memberRow}>{inner}</div>;
            }
            return (
              <button
                key={userId}
                type="button"
                title={`与 ${label} 单聊`}
                disabled={dmBusy}
                onClick={() => void openDm(userId)}
                className={`${styles.memberRow} ${styles.memberRowBtn} ${dm?.userId === userId ? styles.memberRowActive : ""}`}
              >
                {inner}
              </button>
            );
          })}
        </aside>
      </div>
    </main>
  );
}
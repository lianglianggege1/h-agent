"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { MatrixClient, MatrixEvent } from "matrix-js-sdk";
import { MarkdownContent } from "@/components/markdown-content";
import { ChatChartCard } from "@/components/chat-chart-card";
import { chatChartSchema, type ChatChart } from "@/lib/chat-chart";
import { ProgressCard } from "@/components/agui-progress";
import { ChatAttachmentPicker } from "@/components/chat-attachment-picker";
import type { ChatMessageResource } from "@/lib/chat-sessions";
import { apiFetch, ApiError } from "@/lib/http";
import styles from "./team-room.module.css";
import {
  buildMediaContent, buildReplyContent, buildTextContent, isMediaMsgtype, kindLabel, linkifyMxc,
  mediaLabel, mediaMsgtypeForMime, mediaSummary, mentionIdsFor, mentionTrigger, senderLabel, teamMembers,
  toMessageView, toProgressView, CHART_EVENT_TYPE, PROGRESS_EVENT_TYPE,
  type ReplyTarget, type TeamChartReference, type TeamConfig, type TeamMember, type TeamMessageView, type TeamProgressView,
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

type TeamReference = { eventId: string; url: string; body: string; msgtype: string };

type RoomLike = {
  roomId: string;
  timeline: MatrixEvent[];
  getJoinedMembers(): { userId: string }[];
  getMembersWithMembership(membership: string): { userId: string }[];
};

function RoomChart({ reference, mediaUrl, accessToken }: {
  reference: TeamChartReference;
  mediaUrl: string | null;
  accessToken: string;
}) {
  const [chart, setChart] = useState<ChatChart | null>(() => "inline" in reference ? reference.inline : null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (!mediaUrl) return;
    const controller = new AbortController();
    async function load(url: string) {
      try {
        const response = await fetch(url, {
          headers: { Authorization: `Bearer ${accessToken}` }, signal: controller.signal,
        });
        if (!response.ok) throw new Error("图表加载失败");
        const parsed = chatChartSchema.parse(await response.json());
        if (parsed.id !== reference.id) throw new Error("图表身份不匹配");
        if (!controller.signal.aborted) setChart(parsed);
      } catch {
        if (!controller.signal.aborted) setFailed(true);
      }
    }
    void load(mediaUrl);
    return () => controller.abort();
  }, [accessToken, mediaUrl, reference.id]);
  if (chart) return <ChatChartCard content={{ chart }} />;
  return failed || !mediaUrl
    ? <p role="alert">无法加载图表</p>
    : <p role="status">正在加载图表…</p>;
}

function timelineViews(room: RoomLike): TeamMessageView[] {
  return room.timeline
    .map((event) => toMessageView(event.event as never))
    .filter((view): view is TeamMessageView => view !== null);
}

function timelineProgress(room: RoomLike): Record<string, TeamProgressView> {
  const byRun: Record<string, TeamProgressView> = {};
  for (const event of room.timeline) {
    const view = toProgressView(event.event as never);
    if (!view) continue;
    const prev = byRun[view.runId];
    byRun[view.runId] = { ...view, id: prev?.id ?? view.id, ts: prev ? Math.min(prev.ts, view.ts) : view.ts };
  }
  return byRun;
}

function humanSize(size: number): string {
  if (size < 1024) return `${size}B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)}KB`;
  return `${(size / (1024 * 1024)).toFixed(1)}MB`;
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
  const [progressByRoom, setProgressByRoom] = useState<Record<string, Record<string, TeamProgressView>>>({});
  const [replyTo, setReplyTo] = useState<ReplyTarget | null>(null);
  const [references, setReferences] = useState<TeamReference[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [receiptTick, setReceiptTick] = useState(0);
  const markedRef = useRef<Record<string, string>>({});
  const [dmJoined, setDmJoined] = useState(false);
  const [dmBusy, setDmBusy] = useState(false);
  const [input, setInput] = useState("");
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);
  const [error, setError] = useState("");
  const [needsLogin, setNeedsLogin] = useState(false);
  const [sending, setSending] = useState(false);
  const [uploading, setUploading] = useState(false);
  const clientRef = useRef<MatrixClient | null>(null);
  const dmRoomRef = useRef<string | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
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

  const mergeProgress = useCallback((event: MatrixEvent, roomId: string) => {
    const view = toProgressView(event.event as never);
    if (!view) return;
    setProgressByRoom((current) => {
      const room = current[roomId] ?? {};
      const prev = room[view.runId];
      const merged = { ...view, id: prev?.id ?? view.id, ts: prev ? Math.min(prev.ts, view.ts) : view.ts };
      return { ...current, [roomId]: { ...room, [view.runId]: merged } };
    });
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
        setProgressByRoom((current) => ({ ...current, [config.roomId]: timelineProgress(room as unknown as RoomLike) }));
        setRoomMembers(room.getJoinedMembers().map((member) => member.userId));
      };
      const loadDmTimeline = (roomId: string) => {
        const room = c.getRoom(roomId);
        if (!room) return;
        setDmMessagesByRoom((current) => ({ ...current, [roomId]: timelineViews(room as unknown as RoomLike) }));
        setProgressByRoom((current) => ({ ...current, [roomId]: timelineProgress(room as unknown as RoomLike) }));
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
        if (state === "PREPARED" || state === "SYNCING") {
          loadTimeline();
          setReceiptTick((t) => t + 1);
        }
        if (state === "PREPARED") {
          loadKnownDms();
          if (dmRoomRef.current) loadDmTimeline(dmRoomRef.current);
        }
      }) as never);
      c.on("Room.receipt" as never, (() => setReceiptTick((t) => t + 1)) as never);
      c.on("Room.accountData" as never, (() => setReceiptTick((t) => t + 1)) as never);
      c.on("Room.timeline" as never, ((event: MatrixEvent, room: { roomId: string } | undefined, toStart: boolean) => {
        if (toStart || !room) return;
        if (event.getType() === PROGRESS_EVENT_TYPE) {
          mergeProgress(event, room.roomId);
          return;
        }
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
  }, [session, config, appendEvent, appendDmEvent, mergeProgress]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, dmMessagesByRoom, progressByRoom, dm]);

  // 正在 viewing 的房间自动标记已读（fully_read + 公开回执），清除其未读角标。
  useEffect(() => {
    const client = clientRef.current;
    if (!client || !session || !config) return;
    const roomId = dm ? dm.roomId : config.roomId;
    const room = client.getRoom(roomId);
    const last = room?.timeline[room.timeline.length - 1];
    const eventId = last?.getId();
    if (!room || !last || !eventId) return;
    if (markedRef.current[roomId] === eventId) return;
    markedRef.current[roomId] = eventId;
    void client.setRoomReadMarkers(roomId, eventId, last).catch(() => {});
  }, [dm, messages, dmMessagesByRoom, session, config, receiptTick]);

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
      setReplyTo(null);
      setReferences([]);
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
    setReplyTo(null);
    setReferences([]);
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
      const body = references.length ? `${text}\n\n${references.map((ref) => ref.url).join("\n")}` : text;
      const content = replyTo
        ? buildReplyContent(body, mentionIds, replyTo)
        : buildTextContent(body, mentionIds);
      await client.sendEvent(targetRoomId, "m.room.message" as never, content as never, "");
      setInput("");
      setReplyTo(null);
      setReferences([]);
    } catch (e) {
      setError(e instanceof Error ? `发送失败：${e.message}` : "发送失败");
    } finally {
      setSending(false);
    }
  }

  async function attachFiles(files: FileList | null) {
    const client = clientRef.current;
    if (!client || !config || !files || files.length === 0 || uploading) return;
    const targetRoomId = dm ? dm.roomId : config.roomId;
    setUploading(true);
    setError("");
    try {
      for (const file of Array.from(files)) {
        const { content_uri: contentUri } = await client.uploadContent(file);
        const mimetype = file.type || "application/octet-stream";
        await client.sendEvent(
          targetRoomId,
          "m.room.message" as never,
          buildMediaContent(mediaMsgtypeForMime(mimetype), contentUri, file.name, mimetype, file.size) as never,
          "",
        );
      }
    } catch (e) {
      setError(e instanceof Error ? `附件发送失败：${e.message}` : "附件发送失败");
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
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
  const visibleProgress = Object.values(progressByRoom[(dm ? dm.roomId : config.roomId)] ?? {});
  const timelineItems = [
    ...visibleMessages.map((message) => ({ ts: message.ts, key: message.id, element: renderMessage(message) })),
    ...visibleProgress.map((progress) => ({ ts: progress.ts, key: `progress-${progress.runId}`, element: renderProgress(progress) })),
  ].sort((a, b) => a.ts - b.ts);

  const pickerResources: ChatMessageResource[] = visibleMessages
    .filter((message) => isMediaMsgtype(message.msgtype) && message.url)
    .map((message) => {
      const http = mediaHttpUrl(message.url) ?? "";
      return {
        id: message.id, type: message.msgtype, role: "GENERATED",
        viewUrl: http, downloadUrl: http, fileName: message.body,
        mimeType: message.mimetype ?? "application/octet-stream",
        fileSize: message.size > 0 ? message.size : null, width: null, height: null,
      };
    });

  const previewOf = (views: TeamMessageView[]) => {
    const last = views[views.length - 1];
    if (!last) return null;
    const who = last.sender === session.userId ? "自己" : senderLabel(last.sender, session.userId, config);
    const text = isMediaMsgtype(last.msgtype) ? mediaSummary(last) : last.body.replace(/\s+/g, " ");
    return { text: `${who}：${text}`, ts: last.ts };
  };
  const conversations = [
    {
      key: "team", label: "团队聊天室", avatar: "群", active: !dm, roomId: config.roomId,
      preview: previewOf(messages), fallback: "群聊 · 点击成员头像可单聊",
      onClick: closeDm,
    },
    ...members.map((member) => {
      const roomId = dmRooms[member.userId];
      return {
        key: member.userId, label: member.label, avatar: member.label.charAt(0).toUpperCase(),
        active: dm?.userId === member.userId, roomId,
        preview: roomId ? previewOf(dmMessagesByRoom[roomId] ?? []) : null,
        fallback: member.description,
        onClick: () => void openDm(member.userId),
      };
    }),
  ];

  function mediaHttpUrl(mxc: string | null): string | null {
    const client = clientRef.current;
    if (!mxc || !client) return null;
    const base = client.mxcUrlToHttp(mxc, undefined, undefined, undefined, undefined, undefined, true);
    if (!base) return null;
    const token = client.getAccessToken();
    if (!token) return base;
    // Synapse 鉴权媒体要求请求带 token，SDK 生成的 URL 不含凭据，<img>/<a> 无法走 header。
    const url = new URL(base);
    url.searchParams.set("access_token", token);
    return url.href;
  }

  function unreadCount(roomId: string | undefined): number {
    if (!roomId || !session) return 0;
    const room = clientRef.current?.getRoom(roomId);
    if (!room) return 0;
    // 未读消息条数：m.fully_read 标记之后、非本人发送的 m.room.message 数量。
    // 不用 getUnreadNotificationCount（那是推送通知数，群聊非 @ 消息为 0）。
    const marker = room.getAccountData("m.fully_read")?.getContent()?.event_id as string | undefined;
    const timeline = room.timeline;
    const idx = marker ? timeline.findIndex((event) => event.getId() === marker) : -1;
    let count = 0;
    for (let i = idx + 1; i < timeline.length; i++) {
      const event = timeline[i];
      if (!["m.room.message", CHART_EVENT_TYPE].includes(event.getType()) || event.getSender() === session.userId) continue;
      count++;
    }
    return count;
  }

  function receiptOf(message: TeamMessageView) {
    const client = clientRef.current;
    if (!client || !config || !session) return null;
    const roomId = dm ? dm.roomId : config.roomId;
    const room = client.getRoom(roomId);
    const event = room?.findEventById(message.id);
    if (!room || !event) return null;
    const others = room.getJoinedMembers().map((member) => member.userId).filter((userId) => userId !== message.sender);
    if (!others.length) return null;
    const read = others.filter((userId) => room.hasUserReadEvent(userId, message.id));
    const unread = others.filter((userId) => !room.hasUserReadEvent(userId, message.id));
    const names = (list: string[]) => list.map((userId) => senderLabel(userId, session.userId, config)).join("、");
    return (
      <details className={styles.receipt}>
        <summary>{read.length}/{others.length} 已读</summary>
        <span className={styles.receiptList}>已读：{read.length ? names(read) : "无"}；未读：{unread.length ? names(unread) : "无"}</span>
      </details>
    );
  }

  function quoteMessage(message: TeamMessageView) {
    const body = message.chart ? `${message.body}（图表 ID ${message.chart.id}）` : message.body;
    setReplyTo({ eventId: message.id, sender: message.sender, body, msgtype: message.msgtype });
    inputRef.current?.focus();
  }

  // 跨域 Matrix 媒体会忽略 <a download>，改为取字节后 Blob 下载，点击即保存（与普通聊天一致）。
  async function downloadMedia(src: string, fileName: string) {
    try {
      const response = await fetch(src);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName || "下载";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof Error ? `下载失败：${e.message}` : "下载失败");
    }
  }

  function toggleReference(message: TeamMessageView) {
    if (!message.url) return;
    const url = message.url;
    setReferences((current) => current.some((ref) => ref.eventId === message.id)
      ? current.filter((ref) => ref.eventId !== message.id)
      : [...current, { eventId: message.id, url, body: message.body, msgtype: message.msgtype }]);
    inputRef.current?.focus();
  }

  function addReference(message: TeamMessageView) {
    if (!message.url) return;
    const url = message.url;
    setReferences((current) => current.some((ref) => ref.eventId === message.id)
      ? current
      : [...current, { eventId: message.id, url, body: message.body, msgtype: message.msgtype }]);
  }

  function mediaActions(message: TeamMessageView, src: string) {
    const selected = references.some((ref) => ref.eventId === message.id);
    return (
      <span className={styles.mediaActions}>
        <button
          type="button"
          aria-label={`下载 ${message.body}`}
          title={`下载 ${message.body}`}
          onClick={() => void downloadMedia(src, message.body)}
          className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-stone-500 transition-colors hover:bg-stone-100 hover:text-stone-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-400"
        >
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M12 3v12m-5-5 5 5 5-5M5 15v5h14v-5" />
          </svg>
        </button>
        <button
          type="button"
          aria-pressed={selected}
          aria-label={`${selected ? "取消引用" : "引用"} ${message.body}`}
          title={selected ? "取消引用" : "引用素材"}
          onClick={() => toggleReference(message)}
          className={`inline-flex h-8 items-center gap-1.5 rounded-lg px-2.5 text-xs transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-stone-400 ${selected ? "bg-indigo-50 text-indigo-600 hover:bg-indigo-100" : "text-stone-500 hover:bg-stone-100 hover:text-stone-800"}`}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d={selected ? "m5 12 4 4 10-10" : "m9 5-5 5 5 5M4 10h9a7 7 0 0 1 7 7v2"} />
          </svg>
          {selected ? "已引用" : "引用"}
        </button>
      </span>
    );
  }

  function renderMedia(message: TeamMessageView) {
    const src = mediaHttpUrl(message.url);
    if (!src) return <div className={styles.prose}>{message.body}</div>;
    switch (message.msgtype) {
      case "m.image":
        return (
          <div className={styles.mediaWrap}>
            <img src={src} alt={message.body} className={styles.mediaImage} loading="lazy" />
            {mediaActions(message, src)}
          </div>
        );
      case "m.video":
        return (
          <div className={styles.mediaWrap}>
            <video src={src} controls className={styles.mediaVideo} />
            {mediaActions(message, src)}
          </div>
        );
      case "m.audio":
        return (
          <div className={styles.mediaWrap}>
            <audio src={src} controls className={styles.mediaAudio} />
            {mediaActions(message, src)}
          </div>
        );
      default:
        return (
          <div className={styles.mediaWrap}>
            <a href={src} download={message.body} className={styles.fileCard} onClick={(e) => { e.preventDefault(); void downloadMedia(src, message.body); }}>
              <span className={styles.fileIcon} aria-hidden="true">
                <svg viewBox="0 0 24 24"><path d="M14 3v5h5" /><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /></svg>
              </span>
              <span className="min-w-0">
                <span className={`${styles.fileName} block truncate`}>{message.body}</span>
                <span className={styles.fileSize}>
                  {message.mimetype ?? "文件"}{message.size > 0 ? ` · ${humanSize(message.size)}` : ""}
                </span>
              </span>
            </a>
            {mediaActions(message, src)}
          </div>
        );
    }
  }

  function quoteBlockOf(message: TeamMessageView) {
    if (!message.replyTo) return null;
    const original = visibleMessages.find((item) => item.id === message.replyTo);
    const quoted = original
      ? { sender: original.sender, text: isMediaMsgtype(original.msgtype) ? mediaSummary(original) : original.body }
      : message.replyFallback;
    if (!quoted) return null;
    const label = senderLabel(quoted.sender, session!.userId, config!);
    return (
      <div className={styles.quoteBlock}>
        <span className={styles.quoteSender}>{label}</span>
        <span className={styles.quoteText}>{quoted.text.replace(/\s+/g, " ").slice(0, 120)}</span>
      </div>
    );
  }

  function renderProgress(progress: TeamProgressView) {
    const label = senderLabel(progress.sender, session!.userId, config!);
    return (
      <div key={`progress-${progress.runId}`} className={styles.messageRow}>
        <span className={styles.avatar} aria-hidden="true">{label.charAt(0).toUpperCase()}</span>
        <div className={styles.messageBody}>
          <div className={styles.meta}><span className={styles.sender}>{label}</span><span>执行过程</span></div>
          <ProgressCard content={{
            runId: progress.runId, status: progress.status, reasoning: "",
            answerStarted: progress.answerStarted, startedAt: progress.startedAt, processEndedAt: progress.processEndedAt,
            entries: progress.entries, steps: progress.steps,
          }} />
        </div>
      </div>
    );
  }

  function renderMessage(message: TeamMessageView) {
    const isSelf = message.sender === session!.userId;
    const label = senderLabel(message.sender, session!.userId, config!);
    const avatarText = isSelf ? "自己" : label.charAt(0).toUpperCase();
    const time = new Date(message.ts).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
    const media = isMediaMsgtype(message.msgtype);
    const quoteBlock = quoteBlockOf(message);
    const chartUrl = message.chart && "url" in message.chart
      ? clientRef.current?.mxcUrlToHttp(message.chart.url, undefined, undefined, undefined, undefined, undefined, true) ?? null
      : null;
    const textContent = (
      <MarkdownContent content={linkifyMxc(message.body)} resolveMediaUrl={mediaHttpUrl} />
    );
    if (isSelf) {
      return (
        <div key={message.id} className={`${styles.messageRow} ${styles.messageRowSelf}`}>
          <span className={styles.avatar} aria-hidden="true">{avatarText}</span>
          <div className={`${styles.messageBody} ${styles.messageBodySelf}`}>
            <div className={`${styles.meta} ${styles.metaSelf}`}>
              {!media && (
                <button type="button" className={styles.quoteBtn} onClick={() => quoteMessage(message)}>引用</button>
              )}
              <span className={styles.sender}>自己</span><span>{time}</span>
            </div>
            {quoteBlock}
            <div className={media ? `${styles.userBubble} ${styles.mediaBubble}` : styles.userBubble}>
              {media ? renderMedia(message) : <div className={styles.bubbleMarkdown}>{textContent}</div>}
            </div>
            {receiptOf(message)}
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
        <div className={`${styles.messageBody}${message.chart ? ` ${styles.chartBody}` : ""}`}>
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
            {!media && (
              <button type="button" className={styles.quoteBtn} onClick={() => quoteMessage(message)}>引用</button>
            )}
          </div>
          {quoteBlock}
          {message.chart ? <RoomChart reference={message.chart} mediaUrl={chartUrl} accessToken={session!.accessToken} />
            : media ? renderMedia(message) : <div className={styles.prose}>{textContent}</div>}
          {receiptOf(message)}
        </div>
      </div>
    );
  }

  return (
    <main className="chat-workspace flex h-full min-h-0 w-full flex-col overflow-hidden text-stone-900">
      <header className="chat-header z-10 shrink-0 border-b border-stone-200 px-4 py-4 sm:px-8">
        <div className="mx-auto flex w-full items-center gap-3">
          <div className="min-w-0 flex-1">
            <h1 className="truncate font-semibold">{dm ? dm.label : "团队聊天室"}</h1>
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
              <span className={styles.avatarWrap}>
                <span className={styles.avatar} aria-hidden="true">{conv.avatar}</span>
                {unreadCount(conv.roomId) > 0 && (
                  <span className={styles.badge}>{unreadCount(conv.roomId) > 99 ? "99+" : unreadCount(conv.roomId)}</span>
                )}
              </span>
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
              {timelineItems.map((item) => item.element)}
              <div ref={bottomRef} />
            </div>
          </div>
          <form onSubmit={send} className="shrink-0 px-4 pb-4 pt-2 sm:px-8">
            <div className="mx-auto w-full max-w-3xl">
              {error && <p className={styles.error}>{error}</p>}
              <div className={styles.composerWrap}>
                {references.length > 0 && (
                  <div className={styles.refChips} aria-label="待发送引用">
                    {references.map((ref) => (
                      <span key={ref.eventId} className={styles.refChip} title={ref.body}>
                        <span className={styles.refChipLabel}>{mediaLabel(ref.msgtype)}</span>
                        <span className={styles.refChipName}>{ref.body}</span>
                        <button type="button" className={styles.replyChipClose} aria-label={`移除引用 ${ref.body}`} onClick={() => setReferences((current) => current.filter((item) => item.eventId !== ref.eventId))}>×</button>
                      </span>
                    ))}
                  </div>
                )}
                {replyTo && (
                  <div className={styles.replyChip}>
                    <span className={styles.replyChipText}>
                      引用 {senderLabel(replyTo.sender, session.userId, config)}：
                      {(isMediaMsgtype(replyTo.msgtype) ? `${mediaLabel(replyTo.msgtype)} ${replyTo.body}` : replyTo.body).replace(/\s+/g, " ").slice(0, 60)}
                    </span>
                    <button type="button" className={styles.replyChipClose} aria-label="取消引用" onClick={() => setReplyTo(null)}>×</button>
                  </div>
                )}
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
                    <span className={styles.footerLeft}>
                      <button
                        type="button"
                        aria-label="发送附件"
                        title="发送附件"
                        disabled={uploading}
                        className={styles.attach}
                        onClick={() => setPickerOpen(true)}
                      >
                        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48" /></svg>
                      </button>
                      <span className={styles.hint}>{uploading ? "附件上传中…" : "Enter 发送，Shift+Enter 换行"}</span>
                    </span>
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
                {pickerOpen && (
                  <ChatAttachmentPicker
                    resources={pickerResources}
                    hasOlder={false}
                    loadingOlder={false}
                    onLoadOlder={() => {}}
                    onUpload={() => { setPickerOpen(false); fileInputRef.current?.click(); }}
                    onSelect={(resource) => {
                      const message = visibleMessages.find((item) => item.id === resource.id);
                      if (message) addReference(message);
                      setPickerOpen(false);
                      inputRef.current?.focus();
                    }}
                    onClose={() => setPickerOpen(false)}
                  />
                )}
                <input
                  ref={fileInputRef}
                  type="file"
                  multiple
                  hidden
                  onChange={(e) => void attachFiles(e.target.files)}
                />
              </div>
            </div>
          </form>
        </div>
        {!dm && (
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
                  className={`${styles.memberRow} ${styles.memberRowBtn}`}
                >
                  {inner}
                </button>
              );
            })}
          </aside>
        )}
      </div>
    </main>
  );
}
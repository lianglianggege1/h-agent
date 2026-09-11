"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { createLocalAudioTrack, Room, RoomEvent, Track, type LocalAudioTrack } from "livekit-client";
import { createVoiceCall, endVoiceCall, getVoiceCall, originalChatHref, type VoiceCall } from "@/lib/voice-call";

type Caption = { id: string; text: string; speaker: string; final: boolean };
type Connection = { room: Room; track?: LocalAudioTrack; callId?: string; stopped: boolean };
const labels: Record<string, string> = {
  PREPARING: "正在准备通话", CONNECTING: "正在连接语音服务", ACTIVE: "通话中，可以直接说话",
  RECONNECTING: "网络中断，正在重连", ENDING: "正在结束通话", ENDED: "通话已结束", FAILED: "通话连接已结束",
};

function CallScreen() {
  const params = useSearchParams();
  const sessionId = params.get("sessionId") ?? "";
  const router = useRouter();
  const connection = useRef<Connection | null>(null);
  const audioHost = useRef<HTMLDivElement>(null);
  const [state, setState] = useState("IDLE");
  const [error, setError] = useState("");
  const [muted, setMuted] = useState(false);
  const [needsAudio, setNeedsAudio] = useState(false);
  const [captions, setCaptions] = useState<Caption[]>([]);
  const chatUrl = originalChatHref(sessionId);

  async function dispose(current: Connection) {
    current.stopped = true;
    current.track?.stop();
    await current.room.disconnect();
    if (current.callId) await endVoiceCall(current.callId).catch(() => undefined);
  }

  useEffect(() => {
    const leave = () => {
      const current = connection.current;
      if (current) void dispose(current);
    };
    window.addEventListener("pagehide", leave);
    return () => {
      window.removeEventListener("pagehide", leave);
      leave();
      connection.current = null;
    };
  }, []);

  useEffect(() => {
    if (state === "IDLE" || state === "ENDED" || state === "FAILED") return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    let failures = 0;
    async function poll() {
      const current = connection.current;
      if (!current || current.stopped) return;
      try {
        if (current.callId) {
          const call = await getVoiceCall(current.callId);
          if (cancelled || current.stopped) return;
          failures = 0;
          setState(call.state);
          if (call.state === "ENDED" || call.state === "FAILED") {
            await dispose(current);
            if (!cancelled) router.replace(chatUrl);
            return;
          }
        }
      } catch {
        if (++failures >= 5 && !cancelled) {
          setError("通话服务暂时不可达，已关闭麦克风。请返回聊天后重试。");
          setState("FAILED");
          await dispose(current);
          return;
        }
      }
      if (!cancelled) timer = setTimeout(poll, 1500);
    }
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [state, router, chatUrl]);

  async function start() {
    if (connection.current || !sessionId) return;
    if (!window.isSecureContext || !navigator.mediaDevices) {
      setError("请通过设备信任的 HTTPS 地址打开页面，才能使用麦克风。");
      return;
    }
    const room = new Room({ adaptiveStream: true, dynacast: true });
    const current: Connection = { room, stopped: false };
    connection.current = current;
    setState("PREPARING");
    setError("");
    let identity = "";
    room.on(RoomEvent.TrackSubscribed, (track) => {
      if (track.kind === Track.Kind.Audio && !current.stopped) {
        const audio = track.attach();
        audioHost.current?.appendChild(audio);
      }
    });
    room.on(RoomEvent.TrackUnsubscribed, (track) => track.detach().forEach((node) => node.remove()));
    room.on(RoomEvent.AudioPlaybackStatusChanged, () => setNeedsAudio(!room.canPlaybackAudio));
    room.on(RoomEvent.Reconnecting, () => { if (!current.stopped) setState("RECONNECTING"); });
    room.on(RoomEvent.Disconnected, () => {
      if (!current.stopped) {
        setError("语音连接已断开，请返回聊天重试。");
        setState("FAILED");
        void dispose(current);
      }
    });
    room.registerTextStreamHandler("lk.transcription", async (reader, participant) => {
      const id = reader.info.attributes?.["lk.segment_id"] ?? reader.info.id;
      const speaker = participant.identity === identity ? "你" : "Agent";
      let text = "";
      for await (const chunk of reader) {
        if (current.stopped) return;
        text += chunk;
        setCaptions((old) => {
          const next = old.filter((item) => item.id !== id);
          return [...next, { id, text, speaker, final: false }].slice(-100);
        });
      }
      if (!current.stopped) setCaptions((old) => old.map((item) =>
        item.id === id ? { ...item, final: reader.info.attributes?.["lk.transcription_final"] !== "false" } : item));
    });
    try {
      // Ask permission before creating a server-side call.
      current.track = await createLocalAudioTrack({ echoCancellation: true, noiseSuppression: true, autoGainControl: true });
      if (current.stopped) { current.track.stop(); return; }
      // A lost HTTP response retries the same application; it cannot create a second room.
      const requestId = crypto.randomUUID();
      let call: VoiceCall;
      try { call = await createVoiceCall(sessionId, requestId); }
      catch { call = await createVoiceCall(sessionId, requestId); }
      current.callId = call.callId;
      if (current.stopped) { await endVoiceCall(call.callId); return; }
      if (!call.token || !call.livekitUrl || call.state === "ENDING") throw new Error("通话申请已结束，请返回聊天重试。");
      identity = call.participantIdentity ?? "";
      await room.connect(call.livekitUrl, call.token);
      if (current.stopped) { await room.disconnect(); return; }
      await room.localParticipant.publishTrack(current.track);
      await room.startAudio();
      if (!current.stopped) setState(call.state);
    } catch (cause) {
      await dispose(current);
      if (connection.current === current) {
        setState("FAILED");
        setError(cause instanceof Error ? cause.message : "无法建立语音通话");
      }
    }
  }

  async function hangup() {
    setState("ENDING");
    const current = connection.current;
    if (current) await dispose(current);
    router.replace(chatUrl);
  }

  async function toggleMute() {
    const current = connection.current;
    if (!current?.track || current.stopped) return;
    try {
      if (muted) await current.track.unmute(); else await current.track.mute();
      setMuted(!muted);
    } catch { setError("麦克风状态切换失败，请重试。"); }
  }

  return (
    <main className="mx-auto flex min-h-dvh max-w-2xl flex-col gap-6 bg-stone-50 p-6 text-stone-800">
      <header><p className="text-sm text-stone-500">当前聊天 · 语音通话</p>
        <h1 className="mt-2 text-2xl font-semibold">{labels[state] ?? "与 Agent 通话"}</h1>
        <p className="mt-2 text-sm text-stone-500">直接说话即可，Agent 回复时也可以插话。确认的对话文字会保存在当前聊天中。</p>
      </header>
      {error && <p role="alert" className="rounded-xl bg-red-50 p-3 text-red-700">{error}</p>}
      {!sessionId && <p role="alert">请先打开一个普通 Agent 聊天会话，再发起通话。</p>}
      <section aria-label="实时字幕" aria-live="polite" className="flex flex-1 flex-col gap-3 overflow-y-auto rounded-2xl border border-stone-200 bg-white p-4">
        {captions.length === 0 && <p className="text-sm text-stone-400">通话字幕会显示在这里</p>}
        {captions.map((item) => <p key={item.id} className={item.final ? "" : "text-stone-500"}>
          <span className="mr-2 text-sm font-semibold">{item.speaker}</span>{item.text}
        </p>)}
      </section>
      <div ref={audioHost} className="hidden" />
      {needsAudio && <button onClick={() => void connection.current?.room.startAudio()} className="rounded-xl border p-3">点击开启声音</button>}
      <footer className="flex gap-3">
        {state === "IDLE" ? <button disabled={!sessionId} onClick={() => void start()} className="flex-1 rounded-full bg-stone-900 p-4 text-white disabled:opacity-40">开始通话</button>
          : <button disabled={state !== "ACTIVE"} onClick={() => void toggleMute()} className="flex-1 rounded-full border p-4 disabled:opacity-40">{muted ? "开启麦克风" : "静音"}</button>}
        <button onClick={() => void hangup()} className="flex-1 rounded-full bg-red-600 p-4 text-white">{state === "IDLE" || state === "FAILED" ? "返回聊天" : "挂断并返回"}</button>
      </footer>
    </main>
  );
}

export default function CallPage() {
  return <Suspense fallback={<p className="p-6">正在加载通话…</p>}><CallScreen /></Suspense>;
}

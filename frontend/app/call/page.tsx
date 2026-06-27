"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { getCurrentUser } from "@/lib/auth";
import { buildChatHrefFromCall, segmentAssistantText } from "@/lib/call-state";
import { buildChatSendPayload, buildNewSessionPayload, STANDARD_AGENT_ID } from "@/lib/chat-agent-mode";
import { createChatSession, getChatSession } from "@/lib/chat-sessions";
import { apiStream } from "@/lib/http";
import { savePostLoginRedirect } from "@/lib/session";
import {
  cancelCallTurn,
  finalizeCallTurn,
  messageTts,
  previewTts,
  startCallTurn,
  uploadCallTurnChunk,
} from "@/lib/voice";

type CallSpeechRecognitionAlternative = {
  transcript: string;
};

type CallSpeechRecognitionResult = {
  length: number;
  [index: number]: CallSpeechRecognitionAlternative;
};

type CallSpeechRecognitionResultList = {
  length: number;
  [index: number]: CallSpeechRecognitionResult;
};

type CallSpeechRecognitionEvent = {
  results: CallSpeechRecognitionResultList;
};

type CallSpeechRecognitionErrorEvent = {
  error?: string;
};

type CallSpeechRecognition = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: CallSpeechRecognitionEvent) => void) | null;
  onerror: ((event: CallSpeechRecognitionErrorEvent) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
};

type CallSpeechRecognitionConstructor = new () => CallSpeechRecognition;

type SpeechWindow = Window & {
  SpeechRecognition?: CallSpeechRecognitionConstructor;
  webkitSpeechRecognition?: CallSpeechRecognitionConstructor;
};

type PendingUtterance = {
  text: string;
  recordedTurn: RecordedTurn | null;
};

type RecordedTurn = {
  turnId: string;
  uploadPromises: Promise<void>[];
  recordingFailed: boolean;
};

const silenceMs = 3000;
const recordingSaveError = "本轮录音保存失败，文字对话已保留。";
const assistantVoiceSaveError = "回复语音保存失败，文字对话已保留。";

function resultListToTranscript(results: CallSpeechRecognitionResultList) {
  const parts: string[] = [];

  for (let resultIndex = 0; resultIndex < results.length; resultIndex += 1) {
    const result = results[resultIndex];
    for (let alternativeIndex = 0; alternativeIndex < result.length; alternativeIndex += 1) {
      const transcript = result[alternativeIndex]?.transcript?.trim();
      if (transcript) {
        parts.push(transcript);
      }
    }
  }

  return parts.join(" ").trim();
}

function visibleTranscript(fullTranscript: string, committedTranscript: string) {
  if (!committedTranscript) {
    return fullTranscript.trim();
  }

  if (fullTranscript.startsWith(committedTranscript)) {
    return fullTranscript.slice(committedTranscript.length).trim();
  }

  return fullTranscript.trim();
}

function CallPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryString = searchParams.toString();
  const requestedAgentId = searchParams.get("agentId") || STANDARD_AGENT_ID;
  const requestedSessionId = searchParams.get("sessionId");
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(requestedSessionId);
  const [agentName, setAgentName] = useState("普通聊天");
  const [status, setStatus] = useState("正在准备通话");
  const [error, setError] = useState("");
  const [hydrating, setHydrating] = useState(true);
  const [listening, setListening] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [userTranscript, setUserTranscript] = useState("等待开始收音");
  const [assistantText, setAssistantText] = useState("接通后会在这里显示字幕");

  const mountedRef = useRef(false);
  const latestSessionIdRef = useRef<string | null>(requestedSessionId);
  const latestAgentIdRef = useRef(requestedAgentId);
  const speechRecognitionRef = useRef<CallSpeechRecognition | null>(null);
  const silenceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const listeningRef = useRef(false);
  const transcriptRef = useRef("");
  const recognitionTranscriptRef = useRef("");
  const committedRecognitionTranscriptRef = useRef("");
  const streamingRef = useRef(false);
  const pendingUtteranceRef = useRef<PendingUtterance | null>(null);
  const assistantRemainderRef = useRef("");
  const audioQueueRef = useRef<string[]>([]);
  const currentAudioRef = useRef<HTMLAudioElement | null>(null);
  const currentAudioUrlRef = useRef<string | null>(null);
  const audioPlayingRef = useRef(false);
  const playbackGenerationRef = useRef(0);
  const mediaStreamRef = useRef<MediaStream | null>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const currentTurnIdRef = useRef<string | null>(null);
  const startRecordingTurnRef = useRef<(() => Promise<void>) | null>(null);
  const chunkSequenceRef = useRef(0);
  const chunkUploadPromisesRef = useRef<Promise<void>[]>([]);
  const recordingFailedRef = useRef(false);
  const recordingStoppedPromiseRef = useRef<Promise<void> | null>(null);
  const resolveRecordingStoppedRef = useRef<(() => void) | null>(null);

  const setErrorIfMounted = useCallback((message: string) => {
    if (mountedRef.current) {
      setError(message);
    }
  }, []);

  const setStatusIfMounted = useCallback((message: string) => {
    if (mountedRef.current) {
      setStatus(message);
    }
  }, []);

  const clearSilenceTimer = useCallback(() => {
    if (silenceTimerRef.current) {
      clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = null;
    }
  }, []);

  const playNextAudio = useCallback(function playNextAudio() {
    if (audioPlayingRef.current) {
      return;
    }

    const nextUrl = audioQueueRef.current.shift();
    if (!nextUrl) {
      return;
    }

    const generation = playbackGenerationRef.current;
    const audio = new Audio(nextUrl);
    let settled = false;
    currentAudioRef.current = audio;
    currentAudioUrlRef.current = nextUrl;
    audioPlayingRef.current = true;

    const cleanup = () => {
      if (settled) {
        return;
      }
      settled = true;
      URL.revokeObjectURL(nextUrl);
      if (currentAudioRef.current === audio) {
        currentAudioRef.current = null;
        currentAudioUrlRef.current = null;
      }
      audioPlayingRef.current = false;
      if (playbackGenerationRef.current === generation) {
        playNextAudio();
      }
    };

    audio.onended = cleanup;
    audio.onerror = cleanup;
    audio.play().catch(cleanup);
  }, []);

  const enqueueAudio = useCallback(
    (blob: Blob) => {
      const url = URL.createObjectURL(blob);
      audioQueueRef.current.push(url);
      playNextAudio();
    },
    [playNextAudio],
  );

  const stopPlayback = useCallback(() => {
    playbackGenerationRef.current += 1;
    const currentAudio = currentAudioRef.current;
    if (currentAudio) {
      currentAudio.onended = null;
      currentAudio.onerror = null;
      currentAudio.pause();
      currentAudio.removeAttribute("src");
      currentAudio.load();
      currentAudioRef.current = null;
    }
    if (currentAudioUrlRef.current) {
      URL.revokeObjectURL(currentAudioUrlRef.current);
      currentAudioUrlRef.current = null;
    }
    for (const url of audioQueueRef.current) {
      URL.revokeObjectURL(url);
    }
    audioQueueRef.current = [];
    audioPlayingRef.current = false;
  }, []);

  const stopRecordingTurn = useCallback(async () => {
    const recorder = mediaRecorderRef.current;
    if (recorder && recorder.state !== "inactive") {
      try {
        recorder.stop();
      } catch {
        resolveRecordingStoppedRef.current?.();
      }
    } else {
      resolveRecordingStoppedRef.current?.();
    }

    mediaStreamRef.current?.getTracks().forEach((track) => track.stop());
    mediaStreamRef.current = null;
    mediaRecorderRef.current = null;

    await recordingStoppedPromiseRef.current?.catch(() => undefined);
    recordingStoppedPromiseRef.current = null;
    resolveRecordingStoppedRef.current = null;
  }, []);

  const startRecordingTurn = useCallback(async () => {
    const activeSessionId = latestSessionIdRef.current;
    const activeAgentId = latestAgentIdRef.current;
    if (!activeSessionId) {
      return;
    }

    await stopRecordingTurn();
    recordingFailedRef.current = false;
    chunkSequenceRef.current = 0;
    chunkUploadPromisesRef.current = [];

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    mediaStreamRef.current = stream;

    try {
      const turn = await startCallTurn(activeSessionId, activeAgentId);
      currentTurnIdRef.current = turn.turnId;
      const recorder = new MediaRecorder(stream, { mimeType: "audio/webm" });
      mediaRecorderRef.current = recorder;
      recordingStoppedPromiseRef.current = new Promise((resolve) => {
        resolveRecordingStoppedRef.current = resolve;
      });

      recorder.ondataavailable = (event) => {
        if (!event.data || event.data.size === 0) {
          return;
        }

        const sequence = chunkSequenceRef.current;
        chunkSequenceRef.current += 1;
        const upload = uploadCallTurnChunk(
          turn.turnId,
          event.data,
          sequence,
          event.data.type || "audio/webm",
        );
        upload.catch(() => {
          recordingFailedRef.current = true;
          setErrorIfMounted(recordingSaveError);
        });
        chunkUploadPromisesRef.current.push(upload);
      };
      recorder.onstop = () => {
        resolveRecordingStoppedRef.current?.();
      };
      recorder.start(500);
    } catch (recordingError) {
      stream.getTracks().forEach((track) => track.stop());
      mediaStreamRef.current = null;
      currentTurnIdRef.current = null;
      throw recordingError;
    }
  }, [setErrorIfMounted, stopRecordingTurn]);

  const finalizeRecordedTurn = useCallback(
    async (
      recordedTurn: RecordedTurn,
      activeSessionId: string,
      activeAgentId: string,
      messageId: string,
      transcript: string,
    ) => {
      const uploadResults = await Promise.allSettled(recordedTurn.uploadPromises);
      if (recordedTurn.recordingFailed || uploadResults.some((result) => result.status === "rejected")) {
        setErrorIfMounted(recordingSaveError);
        return;
      }

      try {
        await finalizeCallTurn({
          turnId: recordedTurn.turnId,
          sessionId: activeSessionId,
          agentId: activeAgentId,
          messageId,
          transcript,
        });
      } catch {
        setErrorIfMounted(recordingSaveError);
      }
    },
    [setErrorIfMounted],
  );

  const submitUtterance = useCallback(
    async function submitUtterance(textInput: string, queuedTurn?: RecordedTurn | null) {
      const text = textInput.trim();
      const activeSessionId = latestSessionIdRef.current;
      const activeAgentId = latestAgentIdRef.current;
      if (!text || !activeSessionId) {
        return;
      }

      let recordedTurn: RecordedTurn | null = null;
      if (queuedTurn !== undefined) {
        recordedTurn = queuedTurn;
      } else if (currentTurnIdRef.current) {
        recordedTurn = {
          turnId: currentTurnIdRef.current,
          uploadPromises: [...chunkUploadPromisesRef.current],
          recordingFailed: recordingFailedRef.current,
        };
      }

      if (queuedTurn === undefined) {
        await stopRecordingTurn();
        currentTurnIdRef.current = null;
        if (recordedTurn) {
          recordedTurn = {
            ...recordedTurn,
            uploadPromises: [...chunkUploadPromisesRef.current],
            recordingFailed: recordingFailedRef.current,
          };
        }
        if (listeningRef.current) {
          void startRecordingTurnRef.current?.().catch(() => {
            setErrorIfMounted(recordingSaveError);
          });
        }
      }

      if (streamingRef.current) {
        pendingUtteranceRef.current = { text, recordedTurn };
        setStatusIfMounted("等待上一轮回复结束");
        return;
      }

      streamingRef.current = true;
      if (mountedRef.current) {
        setStreaming(true);
        setAssistantText("");
        setError("");
      }
      assistantRemainderRef.current = "";
      setStatusIfMounted("正在思考");
      let assistantMessageId: string | null = null;
      const sideEffects: Promise<void>[] = [];

      try {
        await apiStream(
          "/api/chat/messages/stream",
          {
            method: "POST",
            body: JSON.stringify(
              buildChatSendPayload({
                message: text,
                sessionId: activeSessionId,
                promptId: null,
                agentId: activeAgentId,
              }),
            ),
          },
          {
            onUserMessage(message) {
              if (!recordedTurn) {
                return;
              }
              sideEffects.push(
                finalizeRecordedTurn(recordedTurn, activeSessionId, activeAgentId, message.id, text),
              );
            },
            onChunk(chunk) {
              if (mountedRef.current) {
                setAssistantText((current) => current + chunk);
              }
              const next = segmentAssistantText(chunk, assistantRemainderRef.current);
              assistantRemainderRef.current = next.remainder;
              for (const segment of next.segments) {
                previewTts(activeSessionId, activeAgentId, segment)
                  .then((blob) => {
                    if (mountedRef.current) {
                      enqueueAudio(blob);
                    }
                  })
                  .catch((previewError) => {
                    const message = previewError instanceof Error ? previewError.message : "语音合成失败";
                    setErrorIfMounted(message);
                  });
              }
            },
            onAgentStep(step) {
              setStatusIfMounted(`正在执行：${step.nodeName}`);
            },
            onDone(_content, message) {
              assistantMessageId = message?.id ?? null;
            },
            onBlocked(message) {
              if (mountedRef.current) {
                setAssistantText(message);
              }
            },
            onError(message) {
              setErrorIfMounted(message);
            },
          },
        );

        if (assistantMessageId) {
          try {
            await messageTts(activeSessionId, activeAgentId, assistantMessageId);
          } catch {
            setErrorIfMounted(assistantVoiceSaveError);
          }
        }
        await Promise.allSettled(sideEffects);
      } catch (streamError) {
        const message = streamError instanceof Error ? streamError.message : "发送失败";
        setErrorIfMounted(message);
      } finally {
        streamingRef.current = false;
        assistantRemainderRef.current = "";
        if (mountedRef.current) {
          setStreaming(false);
          setStatus("正在听你说");
        }
        const pending = pendingUtteranceRef.current;
        pendingUtteranceRef.current = null;
        if (pending) {
          void submitUtterance(pending.text, pending.recordedTurn);
        }
      }
    },
    [enqueueAudio, finalizeRecordedTurn, setErrorIfMounted, setStatusIfMounted, stopRecordingTurn],
  );

  useEffect(() => {
    startRecordingTurnRef.current = startRecordingTurn;
  }, [startRecordingTurn]);

  const scheduleSilenceCommit = useCallback(() => {
    clearSilenceTimer();
    silenceTimerRef.current = setTimeout(() => {
      const finalText = transcriptRef.current.trim();
      if (!finalText) {
        return;
      }
      transcriptRef.current = "";
      committedRecognitionTranscriptRef.current = recognitionTranscriptRef.current;
      if (mountedRef.current) {
        setUserTranscript(finalText);
      }
      void submitUtterance(finalText);
    }, silenceMs);
  }, [clearSilenceTimer, submitUtterance]);

  const stopListeningControls = useCallback(
    (updateState = true) => {
      listeningRef.current = false;
      clearSilenceTimer();
      const recognition = speechRecognitionRef.current;
      if (recognition) {
        recognition.onresult = null;
        recognition.onerror = null;
        recognition.onend = null;
        try {
          recognition.stop();
        } catch {
          // Some browsers throw when stop is called while recognition is already idle.
        }
        speechRecognitionRef.current = null;
      }
      if (updateState && mountedRef.current) {
        setListening(false);
      }
    },
    [clearSilenceTimer],
  );

  const startListening = useCallback(() => {
    stopPlayback();
    if (listeningRef.current) {
      setStatusIfMounted("正在听你说");
      return;
    }

    const SpeechRecognitionConstructor =
      (window as SpeechWindow).SpeechRecognition ?? (window as SpeechWindow).webkitSpeechRecognition;
    if (!SpeechRecognitionConstructor) {
      setErrorIfMounted("当前浏览器不支持语音识别。");
      return;
    }

    try {
      const recognition = new SpeechRecognitionConstructor();
      recognition.lang = "zh-CN";
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.onresult = (event) => {
        stopPlayback();
        const fullTranscript = resultListToTranscript(event.results);
        recognitionTranscriptRef.current = fullTranscript;
        const nextTranscript = visibleTranscript(fullTranscript, committedRecognitionTranscriptRef.current);
        transcriptRef.current = nextTranscript;
        if (mountedRef.current) {
          setUserTranscript(nextTranscript || "正在听你说");
          setStatus("正在听你说");
        }
        scheduleSilenceCommit();
      };
      recognition.onerror = (event) => {
        const reason = event.error ? `：${event.error}` : "";
        setErrorIfMounted(`语音识别失败${reason}`);
      };
      recognition.onend = () => {
        if (!listeningRef.current) {
          return;
        }
        try {
          recognition.start();
        } catch {
          listeningRef.current = false;
          if (mountedRef.current) {
            setListening(false);
          }
        }
      };

      speechRecognitionRef.current = recognition;
      listeningRef.current = true;
      if (mountedRef.current) {
        setListening(true);
        setError("");
        setStatus("正在听你说");
      }
      recognition.start();
      void startRecordingTurn().catch(() => {
        setErrorIfMounted(recordingSaveError);
      });
    } catch (recognitionError) {
      listeningRef.current = false;
      const message = recognitionError instanceof Error ? recognitionError.message : "启动语音识别失败";
      setErrorIfMounted(message);
    }
  }, [scheduleSilenceCommit, setErrorIfMounted, setStatusIfMounted, startRecordingTurn, stopPlayback]);

  const hangUp = useCallback(async () => {
    stopListeningControls(false);
    await stopRecordingTurn();
    const turnId = currentTurnIdRef.current;
    currentTurnIdRef.current = null;
    if (turnId) {
      try {
        await cancelCallTurn(turnId);
      } catch {
        setErrorIfMounted(recordingSaveError);
      }
    }
    stopPlayback();

    const activeSessionId = latestSessionIdRef.current;
    const activeAgentId = latestAgentIdRef.current;
    if (activeSessionId) {
      router.replace(buildChatHrefFromCall(activeAgentId, activeSessionId));
      return;
    }
    router.replace("/chat");
  }, [router, setErrorIfMounted, stopListeningControls, stopPlayback, stopRecordingTurn]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      stopListeningControls(false);
      void stopRecordingTurn();
      stopPlayback();
    };
  }, [stopListeningControls, stopPlayback, stopRecordingTurn]);

  useEffect(() => {
    let cancelled = false;
    const redirect = queryString ? `/call?${queryString}` : "/call";

    getCurrentUser()
      .then(async () => {
        if (cancelled) {
          return;
        }
        setHydrating(true);
        setError("");
        setAuthenticated(true);
        try {
          if (requestedSessionId) {
            const session = await getChatSession(requestedSessionId);
            if (cancelled) {
              return;
            }
            const hydratedAgentId = session.agentId || requestedAgentId;
            latestSessionIdRef.current = session.sessionId;
            latestAgentIdRef.current = hydratedAgentId;
            setSessionId(session.sessionId);
            setAgentName(session.agentDisplayName || "普通聊天");
            setStatus("准备就绪");
            return;
          }

          const created = await createChatSession(
            buildNewSessionPayload({
              currentSessionId: null,
              targetAgentId: requestedAgentId,
              promptId: null,
            }),
          );
          if (cancelled) {
            return;
          }
          const hydratedAgentId = created.session.agentId || requestedAgentId;
          latestSessionIdRef.current = created.session.sessionId;
          latestAgentIdRef.current = hydratedAgentId;
          setSessionId(created.session.sessionId);
          setAgentName(created.session.agentDisplayName || "普通聊天");
          setStatus("准备就绪");
        } catch (sessionError) {
          if (!cancelled) {
            setError(sessionError instanceof Error ? sessionError.message : "加载通话会话失败");
            setStatus("会话加载失败");
          }
        }
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        setAuthenticated(false);
        savePostLoginRedirect(redirect);
        router.replace("/auth/login");
      })
      .finally(() => {
        if (!cancelled) {
          setHydrating(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [queryString, requestedAgentId, requestedSessionId, router]);

  if (authenticated !== true) {
    return <main className="min-h-screen bg-stone-950" />;
  }

  return (
    <main className="min-h-screen bg-stone-950 text-white">
      <section className="mx-auto flex min-h-screen w-full max-w-md flex-col px-5 py-6">
        <header className="space-y-2 text-center">
          <p className="text-sm text-stone-300">{hydrating ? "连接中" : status}</p>
          <h1 className="text-2xl font-semibold">{agentName}</h1>
          <p className="truncate text-xs text-stone-500">{sessionId ? `会话 ${sessionId}` : "正在创建会话"}</p>
        </header>

        <div className="flex flex-1 flex-col items-center justify-center gap-8">
          <div className="flex h-36 w-36 items-center justify-center rounded-full border border-white/15 bg-white/10 text-4xl font-semibold shadow-[0_24px_80px_rgba(255,255,255,0.08)]">
            {agentName.slice(0, 1)}
          </div>

          <div className="w-full space-y-4 text-center">
            <div className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3">
              <p className="text-xs text-stone-400">你说</p>
              <p className="mt-2 min-h-10 whitespace-pre-wrap text-sm leading-6 text-stone-100">{userTranscript}</p>
            </div>
            <div className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3">
              <p className="text-xs text-stone-400">回复</p>
              <p className="mt-2 min-h-10 whitespace-pre-wrap text-sm leading-6 text-stone-100">{assistantText}</p>
            </div>
            {error ? (
              <p className="rounded-2xl border border-red-400/30 bg-red-500/10 px-4 py-3 text-sm text-red-100">
                {error}
              </p>
            ) : null}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3 pb-3">
          <button
            className="h-12 rounded-full bg-white text-sm font-semibold text-stone-950 transition hover:bg-stone-200 disabled:cursor-not-allowed disabled:bg-stone-500"
            type="button"
            disabled={hydrating || !sessionId || streaming}
            onClick={startListening}
          >
            {listening ? "听取中" : "开始"}
          </button>
          <button
            className="h-12 rounded-full bg-red-500 text-sm font-semibold text-white transition hover:bg-red-400"
            type="button"
            onClick={() => {
              void hangUp();
            }}
          >
            挂断
          </button>
        </div>
      </section>
    </main>
  );
}

export default function CallPage() {
  return (
    <Suspense fallback={<main className="min-h-screen bg-stone-950" />}>
      <CallPageContent />
    </Suspense>
  );
}

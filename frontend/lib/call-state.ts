export type TranscriptState = {
  transcript: string;
  lastTranscriptAt: number;
};

export function appendTranscript(
  state: TranscriptState,
  nextTranscript: string,
  now: number,
): TranscriptState {
  const normalized = nextTranscript.trim();

  if (normalized.length === 0) {
    return state;
  }

  if (normalized === state.transcript) {
    return state;
  }

  return {
    transcript: normalized,
    lastTranscriptAt: now,
  };
}

export function shouldCommitUtterance({
  transcript,
  lastTranscriptAt,
  now,
  silenceMs = 3000,
}: TranscriptState & { now: number; silenceMs?: number }): boolean {
  return transcript.trim().length > 0 && now - lastTranscriptAt >= silenceMs;
}

export function segmentAssistantText(
  text: string,
  previousRemainder: string,
): { segments: string[]; remainder: string } {
  let buffer = previousRemainder + text;
  const segments: string[] = [];
  const sentenceEndPattern = /[。！？!?]+\s*/g;
  let lastSegmentEnd = 0;
  let match: RegExpExecArray | null;

  while ((match = sentenceEndPattern.exec(buffer)) !== null) {
    const segmentEnd = match.index + match[0].length;
    const segment = buffer.slice(lastSegmentEnd, segmentEnd).trim();

    if (segment.length > 0) {
      segments.push(segment);
    }

    lastSegmentEnd = segmentEnd;
  }

  buffer = buffer.slice(lastSegmentEnd);

  if (buffer.length >= 80) {
    segments.push(buffer);
    buffer = "";
  }

  return {
    segments,
    remainder: buffer,
  };
}

export type AudioQueueState = {
  items: readonly string[];
  playing: boolean;
  enqueue: (url: string) => AudioQueueState;
  startCurrent: () => AudioQueueState;
  finishCurrent: () => AudioQueueState;
  clear: () => AudioQueueState;
};

export function createAudioQueue(items: readonly string[] = [], playing = false): AudioQueueState {
  const frozenItems = Object.freeze([...items]);

  const queue: AudioQueueState = {
    items: frozenItems,
    playing,
    enqueue(url: string) {
      return createAudioQueue([...frozenItems, url], playing);
    },
    startCurrent() {
      return createAudioQueue(frozenItems, frozenItems.length > 0);
    },
    finishCurrent() {
      return createAudioQueue(frozenItems.slice(1), false);
    },
    clear() {
      return createAudioQueue([], false);
    },
  };

  return Object.freeze(queue);
}

export function shouldAcceptPreviewAudio({
  mounted,
  callEnding,
  currentCallGeneration,
  previewCallGeneration,
  currentPlaybackGeneration,
  previewPlaybackGeneration,
}: {
  mounted: boolean;
  callEnding: boolean;
  currentCallGeneration: number;
  previewCallGeneration: number;
  currentPlaybackGeneration: number;
  previewPlaybackGeneration: number;
}): boolean {
  return (
    mounted &&
    !callEnding &&
    currentCallGeneration === previewCallGeneration &&
    currentPlaybackGeneration === previewPlaybackGeneration
  );
}

export function buildCallHref(agentId: string, sessionId: string): string {
  return buildHref("/call", agentId, sessionId);
}

export function buildChatHrefFromCall(agentId: string, sessionId: string): string {
  return buildHref("/chat", agentId, sessionId);
}

function buildHref(pathname: string, agentId: string, sessionId: string): string {
  const params = new URLSearchParams({ agentId, sessionId });

  return `${pathname}?${params.toString()}`;
}

import { apiFetch, apiFormFetch } from "./http";

export type CallTurnStartResponse = {
  turnId: string;
};

export type VoiceResourceResponse = {
  resourceId: string;
  viewUrl: string;
  downloadUrl: string;
  mimeType: string;
  durationMs: number | null;
};

export function startCallTurn(sessionId: string, agentId: string) {
  return apiFetch<CallTurnStartResponse>("/api/voice/call-turns/start", {
    method: "POST",
    body: JSON.stringify({ sessionId, agentId }),
  });
}

export function uploadCallTurnChunk(turnId: string, chunk: Blob, sequence: number, mimeType: string) {
  const form = new FormData();
  form.append("chunk", chunk, `chunk-${sequence}.webm`);
  form.append("sequence", String(sequence));
  form.append("mimeType", mimeType);
  return apiFormFetch<void>(`/api/voice/call-turns/${encodeURIComponent(turnId)}/chunks`, form);
}

export function finalizeCallTurn(input: {
  turnId: string;
  sessionId: string;
  agentId: string;
  messageId: string;
  transcript: string;
}) {
  return apiFetch<VoiceResourceResponse>(`/api/voice/call-turns/${encodeURIComponent(input.turnId)}/finalize`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: input.sessionId,
      agentId: input.agentId,
      messageId: Number(input.messageId),
      transcript: input.transcript,
    }),
  });
}

export function cancelCallTurn(turnId: string) {
  return apiFetch<void>(`/api/voice/call-turns/${encodeURIComponent(turnId)}/cancel`, {
    method: "POST",
  });
}

export async function previewTts(sessionId: string, agentId: string, text: string) {
  const response = await fetch("/api/voice/tts/preview", {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionId, agentId, text }),
  });
  if (!response.ok) {
    throw new Error("语音合成失败");
  }
  return response.blob();
}

export function messageTts(sessionId: string, agentId: string, messageId: string) {
  return apiFetch<VoiceResourceResponse>("/api/voice/tts/message", {
    method: "POST",
    body: JSON.stringify({ sessionId, agentId, messageId: Number(messageId) }),
  });
}

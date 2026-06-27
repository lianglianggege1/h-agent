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

function parseMessageId(messageId: string) {
  const parsed = Number(messageId);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error("消息 ID 无效");
  }
  return parsed;
}

export function finalizeCallTurn(input: {
  turnId: string;
  sessionId: string;
  agentId: string;
  messageId: string;
  transcript: string;
}) {
  const messageId = parseMessageId(input.messageId);
  return apiFetch<VoiceResourceResponse>(`/api/voice/call-turns/${encodeURIComponent(input.turnId)}/finalize`, {
    method: "POST",
    body: JSON.stringify({
      sessionId: input.sessionId,
      agentId: input.agentId,
      messageId,
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
    let message = "语音合成失败";
    try {
      const body = (await response.json()) as { message?: string };
      if (typeof body.message === "string" && body.message.trim().length > 0) {
        message = body.message;
      }
    } catch {
      // Non-JSON upstream errors are common for proxied audio endpoints.
    }
    throw new Error(message);
  }
  return response.blob();
}

export function messageTts(sessionId: string, agentId: string, messageId: string) {
  const parsedMessageId = parseMessageId(messageId);
  return apiFetch<VoiceResourceResponse>("/api/voice/tts/message", {
    method: "POST",
    body: JSON.stringify({ sessionId, agentId, messageId: parsedMessageId }),
  });
}

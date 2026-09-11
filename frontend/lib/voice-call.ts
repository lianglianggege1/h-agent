import { apiFetch } from "./http";
export { buildCallHref, originalChatHref } from "./voice-call-url";

export type VoiceCall = {
  callId: string;
  sessionId: string;
  state: "PREPARING" | "CONNECTING" | "ACTIVE" | "RECONNECTING" | "ENDING" | "ENDED" | "FAILED";
  reason: string | null;
  chatUrl: string;
  token?: string;
  livekitUrl?: string;
  participantIdentity?: string;
};
export const createVoiceCall = (sessionId: string, requestId: string) =>
  apiFetch<VoiceCall>("/api/voice/calls", {
    method: "POST", body: JSON.stringify({ sessionId, requestId }),
  });
export const getVoiceCall = (id: string) =>
  apiFetch<VoiceCall>("/api/voice/calls/" + encodeURIComponent(id));
export const endVoiceCall = (id: string) =>
  apiFetch<VoiceCall>("/api/voice/calls/" + encodeURIComponent(id) + "/end", {
    method: "POST", keepalive: true,
  });

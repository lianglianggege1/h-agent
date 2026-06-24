import { apiFetch } from "./http";

export type ChatSessionMessageType = "USER" | "AI" | "SYSTEM" | "REASONING" | "IMAGE";

export type ChatMessagePayload = {
  prompt: string | null;
  provider: string | null;
  providerRequestId: string | null;
  model: string | null;
  aspectRatio: string | null;
  status: string | null;
  triggerSource: string | null;
  sourceResourceId: string | null;
  parentImageMessageId: string | null;
  operationType: string | null;
} | null;

export type ChatMessageResource = {
  id: string;
  kind: string;
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize: number | null;
  width: number | null;
  height: number | null;
};

export type ChatSessionMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  payload?: ChatMessagePayload;
  resources?: ChatMessageResource[];
  createdAt: string;
};

export type ChatSessionSummary = {
  sessionId: string;
  title: string;
  lastUserMessage: string | null;
  promptId: number | null;
  agentId: string;
  agentDisplayName: string;
  agentDomain: string;
  runtimeType: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
  archived: boolean;
};

export type ChatSessionMeta = {
  sessionId: string;
  title: string;
  promptId: number | null;
  agentId: string;
  agentDisplayName: string;
  agentDomain: string;
  runtimeType: string;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
  archived: boolean;
};

export type ChatSessionMessagesPage = {
  sessionId: string;
  messages: ChatSessionMessage[];
  hasMore: boolean;
  nextBeforeSeq: number | null;
};

export type ChatSessionOpen = {
  session: ChatSessionMeta;
  messagePage: ChatSessionMessagesPage;
};

export type ChatSessionBootstrap = {
  resolution: "created" | "single" | "choose";
  session: ChatSessionOpen | null;
  candidates: ChatSessionSummary[];
};

export function bootstrapChatSession() {
  return apiFetch<ChatSessionBootstrap>("/api/chat/sessions/bootstrap");
}

export function createChatSession(payload?: {
  currentSessionId?: string | null;
  promptId?: number | null;
  agentId?: string | null;
}) {
  return apiFetch<ChatSessionOpen>("/api/chat/sessions/create", {
    method: "POST",
    body: JSON.stringify({
      currentSessionId: payload?.currentSessionId ?? null,
      promptId: payload?.promptId ?? null,
      agentId: payload?.agentId ?? "standard-chat",
    }),
  });
}

export function resolveChatSession(selectedSessionId: string) {
  return apiFetch<ChatSessionOpen>("/api/chat/sessions/resolve", {
    method: "POST",
    body: JSON.stringify({ selectedSessionId }),
  });
}

export function activateHistorySession(targetSessionId: string, currentSessionId?: string | null) {
  return apiFetch<ChatSessionOpen>("/api/chat/sessions/activate", {
    method: "POST",
    body: JSON.stringify({
      targetSessionId,
      currentSessionId: currentSessionId ?? null,
    }),
  });
}

export function getChatSession(sessionId: string) {
  return apiFetch<ChatSessionMeta>(`/api/chat/sessions/${sessionId}`);
}

export function getChatSessionMessages(sessionId: string, limit = 20, beforeSeq?: number | null) {
  const search = new URLSearchParams({
    limit: String(limit),
  });
  if (beforeSeq && beforeSeq > 0) {
    search.set("beforeSeq", String(beforeSeq));
  }
  return apiFetch<ChatSessionMessagesPage>(`/api/chat/sessions/${sessionId}/messages?${search.toString()}`);
}

export function listChatHistory(page = 0, size = 10) {
  const search = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  return apiFetch<ChatSessionSummary[]>(`/api/chat/sessions/history?${search.toString()}`);
}

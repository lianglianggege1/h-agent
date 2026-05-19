import { apiFetch } from "./http";

export type ChatSessionMessage = {
  id: string;
  role: "assistant" | "user";
  content: string;
  createdAt: string;
};

export type ChatSessionSummary = {
  sessionId: string;
  title: string;
  lastUserMessage: string | null;
  promptId: number | null;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
  archived: boolean;
};

export type ChatSessionMeta = {
  sessionId: string;
  title: string;
  promptId: number | null;
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

export function createChatSession(payload?: { currentSessionId?: string | null; promptId?: number | null }) {
  return apiFetch<ChatSessionOpen>("/api/chat/sessions/create", {
    method: "POST",
    body: JSON.stringify({
      currentSessionId: payload?.currentSessionId ?? null,
      promptId: payload?.promptId ?? null,
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

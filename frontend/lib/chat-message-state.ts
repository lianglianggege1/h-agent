import type { ChatSessionMessage, ChatSessionMessageType } from "./chat-sessions";

export type UiChatMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  createdAt?: string;
};

export type RenderableTurn =
  | {
      kind: "user";
      id: string;
      content: string;
    }
  | {
      kind: "assistant";
      id: string;
      reasoning: string | null;
      answer: string;
      blocked: null;
    }
  | {
      kind: "blocked";
      id: string;
      reasoning: string | null;
      answer: "";
      blocked: string;
    };

export function buildPendingAssistantTurn(content: string, seed: number) {
  return {
    userMessage: {
      id: `user-${seed}`,
      role: "user",
      messageType: "USER",
      content,
    } satisfies UiChatMessage,
    reasoningMessage: {
      id: `reasoning-${seed}`,
      role: "assistant",
      messageType: "REASONING",
      content: "",
    } satisfies UiChatMessage,
    assistantMessage: {
      id: `assistant-${seed}`,
      role: "assistant",
      messageType: "AI",
      content: "",
    } satisfies UiChatMessage,
  };
}

export function applyReasoningChunk(messages: UiChatMessage[], reasoningId: string, chunk: string) {
  return messages.map((message) =>
    message.id === reasoningId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyAssistantChunk(messages: UiChatMessage[], assistantId: string, chunk: string) {
  return messages.map((message) =>
    message.id === assistantId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyBlockedState(messages: UiChatMessage[], assistantId: string, blockedMessage: string) {
  return messages.map((message) =>
    message.id === assistantId
      ? { ...message, role: "blocked", messageType: "SYSTEM", content: blockedMessage }
      : message,
  );
}

export function toUiChatMessage(message: ChatSessionMessage): UiChatMessage {
  return {
    id: message.id,
    role: message.role,
    messageType: message.messageType,
    content: message.content,
    createdAt: message.createdAt,
  };
}

export function toRenderableTurns(messages: UiChatMessage[]): RenderableTurn[] {
  const turns: RenderableTurn[] = [];

  for (let index = 0; index < messages.length; index += 1) {
    const current = messages[index];
    if (current.role === "user") {
      turns.push({ kind: "user", id: current.id, content: current.content });
      continue;
    }
    if (current.role === "blocked") {
      turns.push({
        kind: "blocked",
        id: current.id,
        reasoning: null,
        answer: "",
        blocked: current.content,
      });
      continue;
    }
    if (current.messageType === "REASONING") {
      const next = messages[index + 1];
      if (next && next.role === "assistant" && next.messageType === "AI") {
        turns.push({
          kind: "assistant",
          id: next.id,
          reasoning: current.content || null,
          answer: next.content,
          blocked: null,
        });
        index += 1;
        continue;
      }
      if (next && next.role === "blocked" && next.messageType === "SYSTEM") {
        turns.push({
          kind: "blocked",
          id: next.id,
          reasoning: current.content || null,
          answer: "",
          blocked: next.content,
        });
        index += 1;
        continue;
      }
      turns.push({
        kind: "assistant",
        id: current.id,
        reasoning: current.content || null,
        answer: "",
        blocked: null,
      });
      continue;
    }
    turns.push({
      kind: "assistant",
      id: current.id,
      reasoning: null,
      answer: current.content,
      blocked: null,
    });
  }

  return turns;
}

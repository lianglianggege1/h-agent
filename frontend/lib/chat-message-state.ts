import type { ChatMessagePayload, ChatMessageResource, ChatSessionMessage, ChatSessionMessageType } from "./chat-sessions";

export type UiAgentStep = {
  invocationId: string;
  nodeId: string;
  nodeName: string;
  topology: string;
  status: "running" | "completed" | "failed";
  depth: number | null;
  sequence: number;
};

export type UiChatMessage = {
  id: string;
  role: "assistant" | "blocked" | "user";
  messageType: ChatSessionMessageType;
  content: string;
  agentSteps?: UiAgentStep[];
  payload?: ChatMessagePayload;
  resources?: ChatMessageResource[];
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
      agentSteps: UiAgentStep[];
    }
  | {
      kind: "blocked";
      id: string;
      reasoning: string | null;
      answer: "";
      blocked: string;
      agentSteps: UiAgentStep[];
    }
  | {
      kind: "image";
      id: string;
      content: string;
      resources: ChatMessageResource[];
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
      agentSteps: [],
    } satisfies UiChatMessage,
  };
}

export function applyReasoningChunk(messages: UiChatMessage[], reasoningId: string, chunk: string): UiChatMessage[] {
  return messages.map((message) =>
    message.id === reasoningId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyAssistantChunk(messages: UiChatMessage[], assistantId: string, chunk: string): UiChatMessage[] {
  return messages.map((message) =>
    message.id === assistantId ? { ...message, content: `${message.content}${chunk}` } : message,
  );
}

export function applyAgentStep(
  messages: UiChatMessage[],
  assistantId: string,
  step: UiAgentStep,
): UiChatMessage[] {
  return messages.map((message) => {
    if (message.id !== assistantId) {
      return message;
    }

    const existing = message.agentSteps ?? [];
    const index = existing.findIndex((item) => item.invocationId === step.invocationId);
    const agentSteps =
      index >= 0
        ? existing.map((item, itemIndex) => (itemIndex === index ? { ...item, ...step } : item))
        : [...existing, step].sort((left, right) => left.sequence - right.sequence);

    return { ...message, agentSteps };
  });
}

export function applyBlockedState(
  messages: UiChatMessage[],
  assistantId: string,
  blockedMessage: string,
): UiChatMessage[] {
  return messages.map((message) =>
    message.id === assistantId
      ? { ...message, role: "blocked", messageType: "SYSTEM", content: blockedMessage }
      : message,
  );
}

export function applyImageMessage(
  messages: UiChatMessage[],
  assistantId: string,
  imageMessage: ChatSessionMessage,
): UiChatMessage[] {
  const uiMessage = toUiChatMessage(imageMessage);
  let inserted = false;
  const next: UiChatMessage[] = [];

  for (const message of messages) {
    if (message.id === assistantId) {
      next.push(uiMessage, message);
      inserted = true;
      continue;
    }
    next.push(message);
  }

  const withImage = inserted ? next : [...next, uiMessage];
  return withImage.filter((message) => message.messageType !== "REASONING" || message.content.trim().length > 0);
}

export function removeEmptyAssistantPlaceholders(messages: UiChatMessage[]): UiChatMessage[] {
  return messages.filter((message) => {
    const hasAgentSteps = (message.agentSteps ?? []).length > 0;
    return !(
      message.role === "assistant" &&
      message.messageType === "AI" &&
      message.content.trim().length === 0 &&
      !hasAgentSteps
    );
  });
}

export function toUiChatMessage(message: ChatSessionMessage): UiChatMessage {
  return {
    id: message.id,
    role: message.role,
    messageType: message.messageType,
    content: message.content,
    payload: message.payload,
    resources: message.resources ?? [],
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
        agentSteps: current.agentSteps ?? [],
      });
      continue;
    }
    if (current.messageType === "IMAGE") {
      turns.push({
        kind: "image",
        id: current.id,
        content: current.content,
        resources: current.resources ?? [],
      });
      continue;
    }
    if (current.messageType === "REASONING") {
      const next = messages[index + 1];
      const imageMessages: UiChatMessage[] = [];
      let afterImagesIndex = index + 1;
      while (messages[afterImagesIndex]?.messageType === "IMAGE") {
        imageMessages.push(messages[afterImagesIndex]);
        afterImagesIndex += 1;
      }
      const afterImages = messages[afterImagesIndex];
      if (imageMessages.length > 0 && afterImages?.role === "assistant" && afterImages.messageType === "AI") {
        for (const imageMessage of imageMessages) {
          turns.push({
            kind: "image",
            id: imageMessage.id,
            content: imageMessage.content,
            resources: imageMessage.resources ?? [],
          });
        }
        turns.push({
          kind: "assistant",
          id: afterImages.id,
          reasoning: current.content || null,
          answer: afterImages.content,
          blocked: null,
          agentSteps: afterImages.agentSteps ?? [],
        });
        index = afterImagesIndex;
        continue;
      }
      if (imageMessages.length > 0 && afterImages?.role === "blocked" && afterImages.messageType === "SYSTEM") {
        for (const imageMessage of imageMessages) {
          turns.push({
            kind: "image",
            id: imageMessage.id,
            content: imageMessage.content,
            resources: imageMessage.resources ?? [],
          });
        }
        turns.push({
          kind: "blocked",
          id: afterImages.id,
          reasoning: current.content || null,
          answer: "",
          blocked: afterImages.content,
          agentSteps: afterImages.agentSteps ?? [],
        });
        index = afterImagesIndex;
        continue;
      }
      if (next && next.role === "assistant" && next.messageType === "AI") {
        turns.push({
          kind: "assistant",
          id: next.id,
          reasoning: current.content || null,
          answer: next.content,
          blocked: null,
          agentSteps: next.agentSteps ?? [],
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
          agentSteps: next.agentSteps ?? [],
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
        agentSteps: current.agentSteps ?? [],
      });
      continue;
    }
    turns.push({
      kind: "assistant",
      id: current.id,
      reasoning: null,
      answer: current.content,
      blocked: null,
      agentSteps: current.agentSteps ?? [],
    });
  }

  return turns;
}

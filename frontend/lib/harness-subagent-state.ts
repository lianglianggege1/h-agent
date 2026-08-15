import type {
  HarnessAgentEvent,
  HarnessSubagentSummary,
  HarnessSubagentStatus,
} from "./harness-subagent-types";
import type { UiChatMessage } from "./chat-message-state";

export type HarnessUiState = {
  subagentsBySession: Record<string, HarnessSubagentSummary>;
  displayOrder: string[];
  activeRun: {
    runId: string;
    lastSequence: number;
    sequenceContiguous: boolean;
  } | null;
  seenEventIds: Record<string, true>;
  needsCalibration: boolean;
};

export function createHarnessUiState(): HarnessUiState {
  return {
    subagentsBySession: {},
    displayOrder: [],
    activeRun: null,
    seenEventIds: {},
    needsCalibration: false,
  };
}

export function canSubmitSubagentStatus(status: HarnessSubagentStatus) {
  return status === "AVAILABLE"
    || status === "COMPLETED"
    || status === "FAILED";
}

export function replaceHarnessSubagents(
  state: HarnessUiState,
  subagents: HarnessSubagentSummary[],
): HarnessUiState {
  const orderedSubagents = [...subagents].sort(
    (left, right) => left.displayOrder - right.displayOrder,
  );
  return {
    ...state,
    subagentsBySession: Object.fromEntries(
      orderedSubagents.map((subagent) => [subagent.sessionId, subagent]),
    ),
    displayOrder: orderedSubagents.map((subagent) => subagent.sessionId),
    needsCalibration: false,
  };
}

export function orderedHarnessSubagents(state: HarnessUiState) {
  return state.displayOrder
    .map((sessionId) => state.subagentsBySession[sessionId])
    .filter((subagent): subagent is HarnessSubagentSummary => Boolean(subagent));
}

export function applyHarnessEvent(
  state: HarnessUiState,
  event: HarnessAgentEvent,
): HarnessUiState {
  const eventKey = `${event.runId}:${event.eventId}`;
  if (state.seenEventIds[eventKey]) {
    return state;
  }

  const activeRun = state.activeRun;
  const sameRun = activeRun?.runId === event.runId;
  const expectedSequence = sameRun && activeRun ? activeRun.lastSequence + 1 : 1;
  const sequenceContiguous = (sameRun && activeRun ? activeRun.sequenceContiguous : true)
    && event.sequence === expectedSequence;
  let next: HarnessUiState = {
    ...state,
    activeRun: {
      runId: event.runId,
      lastSequence: event.sequence,
      sequenceContiguous,
    },
    seenEventIds: { ...state.seenEventIds, [eventKey]: true },
    needsCalibration: state.needsCalibration
      || event.schema !== "harness.agent-event"
      || event.schemaVersion < 2
      || event.schemaVersion > 3
      || !sequenceContiguous,
  };

  if (event.projection?.subagent) {
    next = upsertSubagent(next, event.projection.subagent);
  }

  return next;
}

export function harnessTranscriptSessionId(event: HarnessAgentEvent): string | null {
  const projectedSessionId = event.projection?.subagent?.sessionId;
  if (projectedSessionId) return projectedSessionId;

  const agentSessionId = event.data.agentSessionId;
  if (typeof agentSessionId === "string" && agentSessionId.trim()) {
    return agentSessionId;
  }
  if (event.eventType === "SUBAGENT_EXPOSED") {
    const exposedSessionId = event.data.sessionId;
    return typeof exposedSessionId === "string" && exposedSessionId.trim()
      ? exposedSessionId
      : null;
  }
  return null;
}

export function harnessTranscriptStreamingState(event: HarnessAgentEvent): boolean | null {
  if (event.source?.scope !== "SUBAGENT") return null;
  const replyId = event.correlation?.replyId;
  if (typeof replyId !== "string" || !replyId.trim()) return null;
  if (event.eventType === "AGENT_START") return true;
  if (event.eventType === "AGENT_END") return false;
  return null;
}

export function isHarnessTranscriptStreamingEvent(event: HarnessAgentEvent): boolean {
  return harnessTranscriptStreamingState(event) !== null;
}

/**
 * 把父 SSE 中扁平化的子 Agent 事件还原为子会话正在生成的消息。
 * 最终落库消息仍由历史接口接管；这些 runtime 消息只负责填补执行中的可见窗口。
 */
export function applyHarnessTranscriptEvent(
  messages: UiChatMessage[],
  event: HarnessAgentEvent,
): UiChatMessage[] {
  const sessionId = harnessTranscriptSessionId(event);
  if (!sessionId) return messages;

  const assignment = event.projection?.subagent?.assignment?.trim();
  let nextMessages = messages;
  if (assignment) {
    const existingIndex = messages.findIndex(
      (message) => message.role === "system" && message.messageType === "SYSTEM",
    );
    const assignmentMessage: UiChatMessage = {
      id: `assignment-${sessionId}`,
      role: "system",
      messageType: "SYSTEM",
      content: assignment,
    };
    nextMessages = existingIndex < 0
      ? [assignmentMessage, ...messages]
      : messages.map((message, index) => (
        index === existingIndex ? { ...message, content: assignment } : message
      ));
  }

  if (event.eventType === "SUBAGENT_EXPOSED") return nextMessages;

  if (event.source?.scope !== "SUBAGENT") return nextMessages;
  const replyId = event.correlation?.replyId;
  if (typeof replyId !== "string" || !replyId.trim()) return nextMessages;

  const reasoningId = `runtime-reasoning-${replyId}`;
  const assistantId = `runtime-assistant-${replyId}`;
  const next = ensureRuntimeTurn(nextMessages, reasoningId, assistantId);
  if (event.eventType === "THINKING_BLOCK_DELTA") {
    const delta = typeof event.data.delta === "string" ? event.data.delta : "";
    return next.map((message) => message.id === reasoningId
      ? { ...message, content: `${message.content}${delta}` }
      : message);
  }
  if (event.eventType === "TEXT_BLOCK_DELTA") {
    const delta = typeof event.data.delta === "string" ? event.data.delta : "";
    return next.map((message) => message.id === assistantId
      ? { ...message, content: `${message.content}${delta}` }
      : message);
  }
  if (event.eventType === "AGENT_RESULT") {
    const content = typeof event.data.content === "string" ? event.data.content : "";
    return next.map((message) => message.id === assistantId
      ? { ...message, content: content || message.content }
      : message);
  }
  return next;
}

export function mergePersistedHarnessMessages(
  persisted: UiChatMessage[],
  current: UiChatMessage[],
  preserveRuntime = false,
): UiChatMessage[] {
  const runtime = current.filter((message) => message.id.startsWith("runtime-"));
  const local = current.filter((message) => (
    message.id.startsWith("user-")
      || message.id.startsWith("reasoning-")
      || message.id.startsWith("assistant-")
  ));
  const persistedHasAssistant = persisted.some(
    (message) => message.role === "assistant" && message.messageType === "AI",
  );
  const transient = preserveRuntime || !persistedHasAssistant ? [...runtime, ...local] : local;
  if (transient.length === 0) return persisted;

  let base = persisted;
  if (preserveRuntime && runtime.some((message) => message.id.startsWith("runtime-assistant-"))) {
    const latestAssistantIndex = persisted.findLastIndex(
      (message) => message.role === "assistant" && message.messageType === "AI",
    );
    if (latestAssistantIndex >= 0) {
      base = persisted.filter((_, index) => index !== latestAssistantIndex);
    }
  }
  return [...base, ...transient];
}

function ensureRuntimeTurn(
  messages: UiChatMessage[],
  reasoningId: string,
  assistantId: string,
): UiChatMessage[] {
  const hasReasoning = messages.some((message) => message.id === reasoningId);
  const hasAssistant = messages.some((message) => message.id === assistantId);
  if (hasReasoning && hasAssistant) return messages;

  const additions: UiChatMessage[] = [];
  if (!hasReasoning) {
    additions.push({
      id: reasoningId,
      role: "assistant",
      messageType: "REASONING",
      content: "",
    });
  }
  if (!hasAssistant) {
    additions.push({
      id: assistantId,
      role: "assistant",
      messageType: "AI",
      content: "",
      agentSteps: [],
    });
  }
  return [...messages, ...additions];
}

function upsertSubagent(
  state: HarnessUiState,
  subagent: HarnessSubagentSummary,
): HarnessUiState {
  const exists = Boolean(state.subagentsBySession[subagent.sessionId]);
  const displayOrder = exists
    ? state.displayOrder
    : [...state.displayOrder, subagent.sessionId].sort((leftId, rightId) => {
        const left = leftId === subagent.sessionId
          ? subagent
          : state.subagentsBySession[leftId];
        const right = rightId === subagent.sessionId
          ? subagent
          : state.subagentsBySession[rightId];
        return left.displayOrder - right.displayOrder;
      });
  return {
    ...state,
    subagentsBySession: {
      ...state.subagentsBySession,
      [subagent.sessionId]: subagent,
    },
    displayOrder,
  };
}

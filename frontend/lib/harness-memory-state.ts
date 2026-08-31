export type MemoryDocumentBaseline = {
  content: string;
  revision: number;
  exists: boolean;
  updatedAt: string | null;
};

export type MemoryPageMode = "loading" | "read" | "edit" | "saving" | "conflict" | "error";

export type MemoryPageState = {
  mode: MemoryPageMode;
  document: MemoryDocumentBaseline | null;
  draft: string;
  revisionStale: boolean;
  loadError: string;
};

const LOAD_ERROR_MESSAGE = "加载长期记忆失败，请稍后重试";

export function initialMemoryPageState(): MemoryPageState {
  return { mode: "loading", document: null, draft: "", revisionStale: false, loadError: "" };
}

export function memoryLoaded(state: MemoryPageState, document: MemoryDocumentBaseline): MemoryPageState {
  return { ...state, mode: "read", document, draft: "", revisionStale: false, loadError: "" };
}

export function memoryLoadFailed(state: MemoryPageState): MemoryPageState {
  return { ...state, mode: "error", loadError: LOAD_ERROR_MESSAGE };
}

export function retryMemoryLoad(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "error") {
    return state;
  }
  return { ...state, mode: "loading", loadError: "" };
}

export function startEditing(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "read" || state.document === null) {
    return state;
  }
  return { ...state, mode: "edit", draft: state.document.content };
}

export function updateDraft(state: MemoryPageState, draft: string): MemoryPageState {
  if (state.mode !== "edit") {
    return state;
  }
  return { ...state, draft };
}

export function cancelEditing(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "edit") {
    return state;
  }
  return { ...state, mode: "read", draft: "" };
}

export function beginSaving(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "edit") {
    return state;
  }
  return { ...state, mode: "saving" };
}

export function saveSucceeded(
  state: MemoryPageState,
  document: MemoryDocumentBaseline,
): MemoryPageState {
  if (state.mode !== "saving") {
    return state;
  }
  return { ...state, mode: "read", document, draft: "", revisionStale: false };
}

export function saveConflict(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "saving") {
    return state;
  }
  return { ...state, mode: "conflict", revisionStale: true };
}

export function saveFailed(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "saving") {
    return state;
  }
  return { ...state, mode: "edit" };
}

export function conflictReloaded(
  state: MemoryPageState,
  document: MemoryDocumentBaseline,
): MemoryPageState {
  if (state.mode !== "conflict") {
    return state;
  }
  return { ...state, mode: "read", document, draft: "", revisionStale: false };
}

export function conflictResumeEditing(state: MemoryPageState): MemoryPageState {
  if (state.mode !== "conflict") {
    return state;
  }
  return { ...state, mode: "edit" };
}

export function staleEditReloaded(
  state: MemoryPageState,
  document: MemoryDocumentBaseline,
): MemoryPageState {
  if (state.mode !== "edit" || !state.revisionStale) {
    return state;
  }
  return { ...state, mode: "read", document, draft: "", revisionStale: false };
}

export function isMemoryDraftDirty(state: MemoryPageState): boolean {
  const editing = state.mode === "edit" || state.mode === "saving" || state.mode === "conflict";
  return editing && state.document !== null && state.draft !== state.document.content;
}

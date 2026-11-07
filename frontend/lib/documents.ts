import { apiFetch } from "./http";

export type DocumentSettings = {
  cloudAvailable: boolean;
  maxBytes: number;
  maxPages: number;
  budgetSeconds: number;
};
export type DocumentBlock = {
  id: string;
  text: string;
  verdict: string;
  score: number;
  canAccept: boolean;
  imageUrl: string;
};
export type DocumentTask = {
  taskId: string;
  sessionId: string;
  anchorMessageId: string | null;
  resourceId: string | null;
  fileName: string;
  status: string;
  detail: string;
  revision: number;
  kind: "PARSE" | "GENERATE" | "REVIEW";
  released: boolean;
  qualityReviewed?: boolean;
  sourceTaskIds?: string[];
  resumeStatus: string;
  outputResourceId?: string | null;
  continuationCancelled: boolean;
  startPage: number;
  endPage: number;
  totalPages: number;
  downloadUrl?: string;
  pages?: { number: number; diagnostic: string; blocks: DocumentBlock[] }[];
};
export type DocumentDecision = {
  blockId: string;
  action: "ACCEPT" | "CORRECT";
  text?: string;
};
const root = (session: string) =>
  `/api/chat/documents/sessions/${encodeURIComponent(session)}`;
export const documentSettings = (session: string) =>
  apiFetch<DocumentSettings>(root(session) + "/settings");
export const documentTasks = (session: string) =>
  apiFetch<DocumentTask[]>(root(session) + "/tasks");
export const reviewDocument = (session: string, id: string) =>
  apiFetch<DocumentTask>(root(session) + "/tasks/" + encodeURIComponent(id));
export const decideDocument = (
  session: string,
  task: DocumentTask,
  blocks: DocumentDecision[],
) =>
  apiFetch<DocumentTask>(
    root(session) + "/tasks/" + encodeURIComponent(task.taskId) + "/decisions",
    {
      method: "POST",
      body: JSON.stringify({ revision: task.revision, blocks }),
    },
  );
export const documentAction = (
  session: string,
  task: DocumentTask,
  action: string,
  hint = "",
  allowUnchanged = false,
) =>
  apiFetch<DocumentTask>(
    root(session) + "/tasks/" + encodeURIComponent(task.taskId) + "/actions",
    {
      method: "POST",
      body: JSON.stringify({
        revision: task.revision,
        action,
        hint,
        allowUnchanged,
      }),
    },
  );

import { apiFetch, apiFormFetch } from "./http";

export type KnowledgeDocument = {
  id: number;
  fileName: string;
  sourceType: string;
  fileType: string | null;
  fileSize: number | null;
  charCount: number | null;
  segmentCount: number | null;
  status: string;
  errorMsg: string | null;
  createdAt: string;
};

export type KnowledgeSegment = {
  text: string;
  metadata: string;
};

export type ManualKnowledgePayload = {
  title: string;
  content: string;
};

export function listKnowledgeDocuments() {
  return apiFetch<KnowledgeDocument[]>("/api/knowledge/documents");
}

export function uploadKnowledgeDocument(file: File) {
  const form = new FormData();
  form.append("file", file);
  return apiFormFetch<number>("/api/knowledge/documents/upload", form);
}

export function createManualKnowledge(payload: ManualKnowledgePayload) {
  return apiFetch<number>("/api/knowledge/documents/manual", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function deleteKnowledgeDocument(docId: number) {
  return apiFetch<null>(`/api/knowledge/documents/${docId}`, {
    method: "DELETE",
  });
}

export function listKnowledgeSegments(docId: number, limit = 20, offset = 0) {
  const search = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  return apiFetch<KnowledgeSegment[]>(`/api/knowledge/documents/${docId}/segments?${search.toString()}`);
}

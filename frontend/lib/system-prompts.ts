import { apiFetch } from "./http";

export type SystemPrompt = {
  id: number;
  name: string;
  content: string;
  isDefault: boolean;
};

export function listSystemPrompts() {
  return apiFetch<SystemPrompt[]>("/api/chat/system-prompts");
}

export function createSystemPrompt(payload: { name: string; content: string }) {
  return apiFetch<SystemPrompt>("/api/chat/system-prompts", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function updateSystemPrompt(id: number, payload: { name: string; content: string }) {
  return apiFetch<SystemPrompt>(`/api/chat/system-prompts/${id}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
}

export function deleteSystemPrompt(id: number) {
  return apiFetch<null>(`/api/chat/system-prompts/${id}`, {
    method: "DELETE",
  });
}

export function setDefaultSystemPrompt(id: number) {
  return apiFetch<SystemPrompt>(`/api/chat/system-prompts/${id}/default`, {
    method: "POST",
    body: JSON.stringify({}),
  });
}

import { ApiError, apiFetch } from "./http";

export type HarnessMemoryDocument = {
  content: string;
  revision: number;
  exists: boolean;
  updatedAt: string | null;
};

export type MemorySection = {
  title: string;
  offset: number;
};

export const HARNESS_MEMORY_MAX_BYTES = 65_536;

const HARNESS_MEMORY_PATH = "/api/me/memory";
const REVISION_CONFLICT_CODE = 40920;
const UNAUTHORIZED_CODE = 40100;

export function getHarnessMemory() {
  return apiFetch<HarnessMemoryDocument>(HARNESS_MEMORY_PATH);
}

export function saveHarnessMemory(content: string, expectedRevision: number) {
  return apiFetch<HarnessMemoryDocument>(HARNESS_MEMORY_PATH, {
    method: "PUT",
    body: JSON.stringify({ content, expectedRevision }),
  });
}

export function isHarnessMemoryConflict(error: unknown): boolean {
  return error instanceof ApiError && error.code === REVISION_CONFLICT_CODE;
}

export function isHarnessMemoryUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.code === UNAUTHORIZED_CODE;
}

export function utf8ByteLength(content: string): number {
  return new TextEncoder().encode(content).length;
}

export function extractMemorySections(content: string): MemorySection[] {
  const sections: MemorySection[] = [];
  let inFencedCode = false;
  let lineOffset = 0;
  for (const line of content.split("\n")) {
    const fenceCandidate = line.trimStart();
    if (fenceCandidate.startsWith("```") || fenceCandidate.startsWith("~~~")) {
      inFencedCode = !inFencedCode;
    } else if (!inFencedCode) {
      const heading = /^##(?!#)\s+(\S.*?)\s*$/.exec(line);
      if (heading) {
        sections.push({ title: heading[1], offset: lineOffset });
      }
    }
    lineOffset += line.length + 1;
  }
  return sections;
}

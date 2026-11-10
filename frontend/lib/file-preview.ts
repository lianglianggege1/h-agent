/** A shared view of uploaded attachments, persisted resources and document outputs. */
export type PreviewResource = {
  id: string;
  fileName: string;
  mimeType: string;
  viewUrl: string;
  downloadUrl: string;
  fileSize: number | null;
};

export type PreviewKind = "image" | "video" | "audio" | "pdf" | "docx" | "markdown" | "text" | "unsupported";
const languages: Record<string, string> = {
  js: "javascript", jsx: "javascript", mjs: "javascript", cjs: "javascript",
  ts: "typescript", tsx: "typescript", py: "python", java: "java", kt: "kotlin",
  c: "c", h: "c", cpp: "cpp", cs: "csharp", go: "go", rs: "rust", rb: "ruby",
  php: "php", sh: "bash", bash: "bash", zsh: "bash", sql: "sql", json: "json",
  yaml: "yaml", yml: "yaml", toml: "ini", ini: "ini", css: "css", scss: "scss",
  html: "xml", htm: "xml", xml: "xml", svg: "xml", vue: "xml", svelte: "xml",
  dockerfile: "dockerfile", makefile: "makefile", r: "r", swift: "swift",
};
export function previewLanguage(fileName: string): string | undefined {
  return languages[fileName.toLowerCase().split(".").at(-1) ?? ""];
}
export function previewKind(resource: Pick<PreviewResource, "fileName" | "mimeType">): PreviewKind {
  const mime = resource.mimeType.toLowerCase().split(";")[0].trim();
  const ext = resource.fileName.toLowerCase().split(".").at(-1);
  // Office ZIPs must never fall through to a text decoder, even with stale MIME metadata.
  if (ext === "docx" || mime === "application/vnd.openxmlformats-officedocument.wordprocessingml.document") return "docx";
  if (["xlsx", "xls", "pptx", "ppt", "doc", "zip"].includes(ext ?? "")) return "unsupported";
  if (ext === "pdf" || mime === "application/pdf") return "pdf";
  if (["md", "markdown", "mdown"].includes(ext ?? "") || mime === "text/markdown") return "markdown";
  if (mime === "image/svg+xml" || ext === "svg") return "text";
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("video/")) return "video";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("text/") || previewLanguage(resource.fileName)
    || ["txt", "log", "csv", "tsv"].includes(ext ?? "")
    || ["application/json", "application/xml", "application/javascript", "application/x-yaml"].includes(mime)) return "text";
  return "unsupported";
}

export function formatPreviewSize(bytes: number | null): string | null {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return null;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toLocaleString("zh-CN", { maximumFractionDigits: 1 })} KB`;
  return `${(bytes / 1024 ** 2).toLocaleString("zh-CN", { maximumFractionDigits: 1 })} MB`;
}

export function previewLimit(kind: PreviewKind): number {
  return (kind === "text" || kind === "markdown" ? 2 : kind === "docx" ? 10 : 50) * 1024 ** 2;
}

/** Enforce limits on the response stream as well as the untrusted stored file size. */
export async function readPreviewBytes(resource: PreviewResource, kind: PreviewKind, signal: AbortSignal): Promise<Uint8Array> {
  const limit = previewLimit(kind);
  const tooLarge = () => new Error(`文件超过 ${formatPreviewSize(limit)} 的预览上限，请下载查看。`);
  if (resource.fileSize != null && resource.fileSize > limit) throw tooLarge();
  const response = await fetch(resource.viewUrl, { credentials: "include", signal });
  if (!response.ok) {
    if (response.status === 401) throw new Error("登录已过期，请重新登录后重试。");
    if (response.status === 403) throw new Error("你没有权限查看这个文件。");
    if (response.status === 404 || response.status === 410) throw new Error("文件不存在或已被清理。");
    throw new Error(`文件加载失败（${response.status}），请重试。`);
  }
  if (Number(response.headers.get("content-length")) > limit) {
    await response.body?.cancel();
    throw tooLarge();
  }
  const reader = response.body?.getReader();
  if (!reader) throw new Error("无法读取文件内容，请重试或下载查看。");
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) { await reader.cancel(); throw tooLarge(); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return bytes;
}

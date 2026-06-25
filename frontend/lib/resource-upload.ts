export type UploadedResource = {
  resourceId: string;
  kind: string;
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
};

export function uploadChatResource(file: File, sessionId: string): Promise<UploadedResource> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("sessionId", sessionId);
  return fetch("/api/chat/resources/upload", {
    method: "POST",
    credentials: "include",
    body: formData,
  }).then(async (res) => {
    if (!res.ok) {
      const text = await res.text();
      throw new Error(text || "上传失败");
    }
    return res.json();
  });
}

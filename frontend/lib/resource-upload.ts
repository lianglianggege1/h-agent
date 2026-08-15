export type UploadedResource = {
  resourceId: string;
  type: string;
  role: string;
  source: "UPLOAD" | "HISTORY";
  viewUrl: string;
  downloadUrl: string;
  fileName: string;
  mimeType: string;
  fileSize: number;
};

export function uploadChatResource(file: File, role: "ATTACHMENT" | "REFERENCE" = "ATTACHMENT"): Promise<UploadedResource> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("role", role);
  return fetch("/api/chat/resources/upload", {
    method: "POST",
    credentials: "include",
    body: formData,
  }).then(async (res) => {
    if (!res.ok) {
      const text = await res.text();
      throw new Error(text || "上传失败");
    }
    const uploaded = await res.json();
    return { ...uploaded, source: "UPLOAD" };
  });
}

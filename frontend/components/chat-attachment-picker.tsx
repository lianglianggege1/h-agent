"use client";

import { useEffect, useRef, useState } from "react";
import type { ChatMessageResource } from "@/lib/chat-sessions";

export function ChatAttachmentPicker({
  resources,
  hasOlder,
  loadingOlder,
  onLoadOlder,
  onUpload,
  onSelect,
  onClose,
}: {
  resources: ChatMessageResource[];
  hasOlder: boolean;
  loadingOlder: boolean;
  onLoadOlder: () => void;
  onUpload: () => void;
  onSelect: (resource: ChatMessageResource) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [history, setHistory] = useState(false);
  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  return (
    <dialog
      ref={dialog}
      onCancel={onClose}
      aria-label={history ? "选择历史素材" : "添加附件"}
      className="fixed inset-0 m-auto max-h-[80dvh] w-[calc(100%-2rem)] max-w-sm overflow-y-auto rounded-3xl border border-stone-200 bg-[#f8f5ec] p-4 text-stone-800 shadow-xl backdrop:bg-stone-950/30"
    >
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold">
          {history ? "选择历史素材" : "添加附件"}
        </h2>
        <button
          type="button"
          className="text-xs text-stone-500"
          onClick={onClose}
        >
          关闭
        </button>
      </div>
      {!history ? (
        <div className="space-y-2">
          <button
            type="button"
            className="agui-button w-full text-left"
            onClick={onUpload}
          >
            上传本地文件
          </button>
          <button
            type="button"
            className="agui-button w-full text-left"
            onClick={() => setHistory(true)}
          >
            从历史选择
          </button>
        </div>
      ) : (
        <>
          <p className="mb-3 text-xs leading-5 text-stone-500">
            选择当前会话中的图片、音视频、文档或其他文件，用于下一次请求。
          </p>
          {resources.length ? (
            <div className="grid grid-cols-3 gap-2">
              {resources.map((resource) => (
                <button
                  type="button"
                  key={resource.id}
                  aria-label={"引用素材 " + resource.fileName}
                  className="aspect-square overflow-hidden rounded-xl border border-stone-200 bg-white focus-visible:outline-2 focus-visible:outline-amber-700"
                  onClick={() => onSelect(resource)}
                >
                  {resource.mimeType.startsWith("image/") ? (
                    <picture>
                      <img
                        src={resource.viewUrl}
                        alt={resource.fileName}
                        className="h-full w-full object-cover"
                      />
                    </picture>
                  ) : resource.mimeType.startsWith("video/") ? (
                    <video
                      src={resource.viewUrl}
                      muted
                      preload="metadata"
                      className="h-full w-full object-cover"
                    />
                  ) : resource.mimeType.startsWith("audio/") ? (
                    <span className="flex h-full flex-col items-center justify-center gap-1 px-2">
                      <span aria-hidden="true" className="text-2xl">
                        ♫
                      </span>
                      <span className="max-w-full truncate text-xs">
                        {resource.fileName}
                      </span>
                    </span>
                  ) : (
                    <span
                      className="flex h-full flex-col items-center justify-center gap-2 p-2"
                      title={resource.fileName}
                    >
                      <svg
                        width="24"
                        height="24"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.7"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                      >
                        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8ZM14 2v6h6M8 13h8M8 17h5" />
                      </svg>
                      <span className="line-clamp-2 max-w-full break-all text-xs">
                        {resource.fileName}
                      </span>
                      <span className="text-[10px] text-stone-500">
                        {resource.mimeType === "application/pdf"
                          ? "PDF"
                          : (resource.fileName
                              .match(/\.([a-z0-9]{1,12})$/i)?.[1]
                              .toUpperCase() ?? "文件")}
                      </span>
                    </span>
                  )}
                </button>
              ))}
            </div>
          ) : (
            <p className="rounded-xl bg-white/70 p-4 text-center text-sm text-stone-500">
              当前已加载的历史中没有可引用的素材
            </p>
          )}
          {hasOlder ? (
            <button
              type="button"
              disabled={loadingOlder}
              className="agui-button mt-3 w-full"
              onClick={onLoadOlder}
            >
              {loadingOlder ? "加载中…" : "加载更早的历史素材"}
            </button>
          ) : null}
          <button
            type="button"
            className="mt-4 text-xs text-amber-800"
            onClick={() => setHistory(false)}
          >
            返回
          </button>
        </>
      )}
    </dialog>
  );
}

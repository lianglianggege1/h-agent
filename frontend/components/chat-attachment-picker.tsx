"use client";

import { useEffect, useRef, useState } from "react";
import type { ChatMessageResource } from "@/lib/chat-sessions";

export function ChatAttachmentPicker({ images, hasOlder, loadingOlder, onLoadOlder, onUpload, onSelect, onClose }: {
  images: ChatMessageResource[];
  hasOlder: boolean;
  loadingOlder: boolean;
  onLoadOlder: () => void;
  onUpload: () => void;
  onSelect: (resource: ChatMessageResource) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [history, setHistory] = useState(false);
  useEffect(() => { dialog.current?.showModal(); }, []);

  return <dialog ref={dialog} onCancel={onClose} aria-label={history ? "选择历史图片" : "添加附件"}
    className="fixed inset-0 m-auto max-h-[80dvh] w-[calc(100%-2rem)] max-w-sm overflow-y-auto rounded-3xl border border-stone-200 bg-[#f8f5ec] p-4 text-stone-800 shadow-xl backdrop:bg-stone-950/30">
    <div className="mb-4 flex items-center justify-between gap-3">
      <h2 className="text-sm font-semibold">{history ? "选择历史图片" : "添加附件"}</h2>
      <button type="button" className="text-xs text-stone-500" onClick={onClose}>关闭</button>
    </div>
    {!history ? <div className="space-y-2">
      <button type="button" className="agui-button w-full text-left" onClick={onUpload}>上传本地文件</button>
      <button type="button" className="agui-button w-full text-left" onClick={() => setHistory(true)}>从历史选择</button>
    </div> : <>
      <p className="mb-3 text-xs leading-5 text-stone-500">选择当前会话生成过的图片，用作下一次请求的参考图。</p>
      {images.length ? <div className="grid grid-cols-3 gap-2">
        {images.map((resource) => <button type="button" key={resource.id} aria-label={"选择参考图 " + resource.fileName}
          className="aspect-square overflow-hidden rounded-xl border border-stone-200 bg-white focus-visible:outline-2 focus-visible:outline-amber-700"
          onClick={() => onSelect(resource)}>
          <picture><img src={resource.viewUrl} alt={resource.fileName} className="h-full w-full object-cover" /></picture>
        </button>)}
      </div> : <p className="rounded-xl bg-white/70 p-4 text-center text-sm text-stone-500">当前已加载的历史中没有生成图片</p>}
      {hasOlder ? <button type="button" disabled={loadingOlder} className="agui-button mt-3 w-full" onClick={onLoadOlder}>{loadingOlder ? "加载中…" : "加载更早的历史图片"}</button> : null}
      <button type="button" className="mt-4 text-xs text-amber-800" onClick={() => setHistory(false)}>返回</button>
    </>}
  </dialog>;
}

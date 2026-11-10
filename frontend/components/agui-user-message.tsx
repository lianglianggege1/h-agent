"use client";

import { CopilotChatUserMessage, type CopilotChatUserMessageProps } from "@copilotkit/react-core/v2";
import { createContext, useContext, useLayoutEffect, useRef, useState, type HTMLAttributes } from "react";
import { useFilePreview } from "./file-preview";
import type { PreviewResource } from "@/lib/file-preview";
import styles from "./agui-user-message.module.css";

export const UserMessageEditContext = createContext<{
  latestId: string | null; disabled: boolean; save: (id: string, text: string) => Promise<void>;
} | null>(null);

function UserMessageToolbar(props: HTMLAttributes<HTMLDivElement>) {
  return <div {...props} data-testid="copilot-user-toolbar" className={styles.actions} />;
}

function ReferenceUserMessageView(props: CopilotChatUserMessageProps) {
  const actions = useContext(UserMessageEditContext);
  const preview = useFilePreview();
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const editor = useRef<HTMLTextAreaElement>(null);
  const messageElement = useRef<HTMLDivElement>(null);
  const editable = actions?.latestId === props.message.id;
  const text = typeof props.message.content === "string" ? props.message.content
    : props.message.content.filter((part) => part.type === "text").map((part) => part.text).join("");
  const references = Array.isArray(props.message.content) ? props.message.content.flatMap((part, index) =>
    (part.type === "image" || part.type === "video" || part.type === "audio") && part.source.type === "url" && part.metadata?.hAgentReference === true
      ? [{ id: String(part.metadata.resourceId ?? index), url: part.source.value, kind: part.type, resource: part.metadata.resource as PreviewResource | undefined, group: part.metadata.previewResources as PreviewResource[] | undefined }] : []) : [];
  useLayoutEffect(() => {
    if (!editing || !editor.current) return;
    const node = editor.current;
    const fit = () => { node.style.height = "auto"; node.style.height = `${node.scrollHeight + 2}px`; };
    fit();
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(fit);
    observer?.observe(node);
    return () => observer?.disconnect();
  }, [draft, editing]);

  function save() {
    if (!draft.trim() || draft.trim() === text.trim() || draft.trim().length > 4000 || actions?.disabled) return;
    setEditing(false);
    void actions?.save(props.message.id, draft.trim());
  }

  function cancel() {
    setEditing(false);
    requestAnimationFrame(() => messageElement.current?.querySelector<HTMLButtonElement>('[data-testid="copilot-edit-button"]')?.focus());
  }

  return <CopilotChatUserMessage {...props} toolbar={UserMessageToolbar}
    copyButton={{ className: styles.iconButton, title: "复制消息" }}
    editButton={{ className: styles.iconButton, title: "编辑消息", disabled: actions?.disabled }}
    onEditMessage={editable ? () => { setDraft(text); setEditing(true); } : undefined}>
    {({ messageRenderer, toolbar }) => <div ref={messageElement} data-copilotkit data-testid="copilot-user-message"
      data-message-id={props.message.id}
      data-latest={editable || undefined}
      className={`${styles.message} copilotKitMessage copilotKitUserMessage cpk:flex cpk:flex-col cpk:items-end cpk:group cpk:pt-10 ${props.className ?? ""}`}>
      {editing && editable ? <form className={styles.editor} onSubmit={(event) => {
        event.preventDefault();
        save();
      }}>
        <textarea ref={editor} aria-label="修改消息文字" autoFocus maxLength={4000} rows={1} value={draft}
          disabled={actions?.disabled} onChange={(event) => setDraft(event.target.value)}
          onFocus={(event) => event.currentTarget.setSelectionRange(event.currentTarget.value.length, event.currentTarget.value.length)}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
            if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); cancel(); }
            if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.stopPropagation(); save(); }
          }} />
        <div className={styles.editorFooter}>
          <span className={styles.shortcut}>Enter 保存 · Shift+Enter 换行</span>
          <div className={styles.editorActions}>
            <button type="button" className={styles.iconButton} aria-label="取消编辑" title="取消编辑（Esc）" onClick={cancel}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5 5 10 10M15 5 5 15" /></svg>
            </button>
            <button type="submit" className={`${styles.iconButton} ${styles.confirm}`} aria-label="保存并重新回答" title="保存并重新回答（Enter）"
              disabled={actions?.disabled || !draft.trim() || draft.trim() === text.trim()}>
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4 10 4 4 8-8" /></svg>
            </button>
          </div>
        </div>
      </form> : messageRenderer}
      {references.length ? <div className={styles.references} aria-label="此消息引用的素材">
        {references.map((reference, index) => <a key={reference.id} href={reference.url} target="_blank" rel="noreferrer"
          onClick={(event) => {
            if (!preview) return;
            event.preventDefault();
            const items = references.map((ref, i) => ref.resource ?? { id: ref.id, fileName: `引用素材 ${i + 1}`, mimeType: `${ref.kind}/*`, viewUrl: ref.url, downloadUrl: ref.url, fileSize: null });
            preview.open(reference.group?.length ? reference.group : items, reference.id);
          }}
          className={styles.thumbnail} aria-label={`查看引用${reference.kind === "video" ? "视频" : reference.kind === "audio" ? "音频" : "图片"} ${index + 1}`} title="查看引用素材">
          {reference.kind === "image" ? <picture><img src={reference.url} alt={`引用图片 ${index + 1}`} /></picture>
            : reference.kind === "video" ? <><video src={reference.url} muted preload="metadata" /><span className={styles.play} aria-hidden="true">▶</span></>
            : <span className={styles.audio} aria-hidden="true">♫</span>}
        </a>)}
      </div> : null}
      {!editing || !editable ? toolbar : null}
    </div>}
  </CopilotChatUserMessage>;
}

export const ReferencedUserMessage = Object.assign(ReferenceUserMessageView, CopilotChatUserMessage);

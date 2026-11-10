"use client";

import { Component, createContext, lazy, Suspense, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { formatPreviewSize, type PreviewResource } from "@/lib/file-preview";
import styles from "./file-preview.module.css";

const PreviewContent = lazy(() => import("./file-preview-content"));
const PreviewContext = createContext<{ open: (resources: PreviewResource[], id: string) => void } | null>(null);
export const useFilePreview = () => useContext(PreviewContext);

class PreviewBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <p className={styles.message} role="alert">预览组件加载失败，请刷新后重试或下载文件查看。</p> : this.props.children;
  }
}

/** One preview per chat; the conversation remains mounted and usable on wide screens. */
export function FilePreviewProvider({ children }: { children: ReactNode }) {
  const [selection, setSelection] = useState<{ resources: PreviewResource[]; index: number } | null>(null);
  const [narrow, setNarrow] = useState(false);
  const workspace = useRef<HTMLDivElement>(null);
  const panel = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLElement | null>(null);
  const open = useCallback((resources: PreviewResource[], id: string) => {
    const index = resources.findIndex((item) => item.id === id);
    if (index < 0) return;
    trigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setSelection({ resources, index });
  }, []);
  const close = useCallback(() => {
    setSelection(null);
    if (trigger.current?.isConnected) trigger.current.focus();
  }, []);
  const opened = selection !== null;
  useEffect(() => {
    const element = workspace.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setNarrow(entry.contentRect.width < 900));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!opened) return;
    panel.current?.querySelector<HTMLButtonElement>('button[aria-label="关闭预览"]')?.focus();
    const keydown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing) return;
      const focusInPanel = panel.current?.contains(document.activeElement);
      const focusInChat = workspace.current?.contains(document.activeElement);
      // A parent chat and a child-agent chat can each have a preview open.
      // Only the preview containing focus should handle Escape.
      if (!focusInPanel && (narrow || !focusInChat)) return;
      if (event.key === "Escape") { event.preventDefault(); close(); }
      if (event.key !== "Tab" || !narrow) return;
      const buttons = panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input, video[controls], audio[controls], [tabindex="0"]');
      const items = Array.from(buttons ?? []).filter(item => item.getClientRects().length);
      const first = items[0], last = items.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener("keydown", keydown);
    return () => document.removeEventListener("keydown", keydown);
  }, [opened, narrow, close]);

  const resource = selection?.resources[selection.index];
  const previewPanel = selection && resource ? <aside ref={panel} className={`${styles.panel} ${narrow ? styles.fullscreen : ""}`} role={narrow ? "dialog" : "region"}
        aria-modal={narrow || undefined} aria-label="文件预览">
        <header className={styles.header}>
          <div className={styles.title}><strong title={resource.fileName}>{resource.fileName || "未命名文件"}</strong>
            <span>{[resource.fileName.split(".").at(-1)?.toUpperCase(), formatPreviewSize(resource.fileSize)].filter(Boolean).join(" · ")}</span></div>
          <a className={styles.iconButton} href={resource.downloadUrl} download={resource.fileName} aria-label={`下载 ${resource.fileName}`} title="下载文件">↓</a>
          <button type="button" className={styles.iconButton} onClick={close} aria-label="关闭预览" title="关闭预览（Esc）">×</button>
        </header>
        {selection.resources.length > 1 ? <nav className={styles.navigation} aria-label="切换附件">
          <button type="button" disabled={selection.index === 0} onClick={() => setSelection({ ...selection, index: selection.index - 1 })}>← 上一个</button>
          <span aria-live="polite">{selection.index + 1} / {selection.resources.length}</span>
          <button type="button" disabled={selection.index === selection.resources.length - 1} onClick={() => setSelection({ ...selection, index: selection.index + 1 })}>下一个 →</button>
        </nav> : null}
        <div className={styles.content}>
          <PreviewBoundary key={resource.id}><Suspense fallback={<p className={styles.message} role="status">正在加载预览…</p>}>
            <PreviewContent key={`${resource.id}:${resource.viewUrl}`} resource={resource} />
          </Suspense></PreviewBoundary>
        </div>
      </aside> : null;
  return <PreviewContext.Provider value={{ open }}>
    <div ref={workspace} className={styles.workspace}>
      <div className={styles.conversation} inert={opened && narrow}>{children}</div>
      {previewPanel && (narrow ? createPortal(previewPanel, document.body) : previewPanel)}
    </div>
  </PreviewContext.Provider>;
}

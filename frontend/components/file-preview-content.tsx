"use client";

import { useEffect, useRef, useState } from "react";
import { previewKind, previewLanguage, readPreviewBytes, type PreviewResource } from "@/lib/file-preview";
import { MarkdownContent } from "./markdown-content";
import styles from "./file-preview.module.css";
import "highlight.js/styles/github.css";

function Failure({ message, retry, resource }: { message: string; retry?: () => void; resource: PreviewResource }) {
  return <div className={styles.message} role="alert"><strong>无法预览这个文件</strong><p>{message}</p>
    <div>{retry ? <button type="button" onClick={retry}>重试</button> : null}<a href={resource.downloadUrl} download={resource.fileName}>下载文件</a></div></div>;
}

function PdfPreview({ bytes, resource, retry }: { bytes: Uint8Array; resource: PreviewResource; retry: () => void }) {
  const [document, setDocument] = useState<import("pdfjs-dist").PDFDocumentProxy | null>(null);
  const [page, setPage] = useState(1);
  const [error, setError] = useState("");
  const [rendering, setRendering] = useState(true);
  const [width, setWidth] = useState(480);
  const host = useRef<HTMLDivElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    let disposed = false;
    let task: import("pdfjs-dist").PDFDocumentLoadingTask | undefined;
    void import("pdfjs-dist/legacy/build/pdf.mjs").then(async (pdfjs) => {
      if (disposed) return;
      const base = `/pdfjs/${pdfjs.version}`;
      pdfjs.GlobalWorkerOptions.workerSrc = `${base}/pdf.worker.min.mjs`;
      task = pdfjs.getDocument({ data: bytes.slice(),
        cMapUrl: `${base}/cmaps/`, cMapPacked: true, standardFontDataUrl: `${base}/standard_fonts/`, wasmUrl: `${base}/wasm/` });
      task.onPassword = () => { if (!disposed) setError("PDF 已加密，请下载后使用阅读器打开。"); void task?.destroy(); };
      const result = await task.promise;
      if (!disposed) setDocument(result);
    }).catch(() => { if (!disposed) setError(current => current || "PDF 已损坏或无法解析，请重试或下载查看。"); });
    return () => { disposed = true; void task?.destroy(); };
  }, [bytes]);
  useEffect(() => {
    if (!host.current) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(120, entry.contentRect.width - 32)));
    observer.observe(host.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    if (!document || !canvas.current) return;
    let disposed = false;
    let task: import("pdfjs-dist").RenderTask | undefined;
    const element = canvas.current;
    void document.getPage(page).then(async (pdfPage) => {
      if (disposed) return;
      setRendering(true);
      const viewport = pdfPage.getViewport({ scale: Math.min(width / pdfPage.getViewport({ scale: 1 }).width, 2) });
      const ratio = Math.min(window.devicePixelRatio || 1, 2);
      element.width = Math.floor(viewport.width * ratio);
      element.height = Math.floor(viewport.height * ratio);
      element.style.width = `${viewport.width}px`;
      element.style.height = `${viewport.height}px`;
      task = pdfPage.render({ canvas: element, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] });
      await task.promise;
      if (!disposed) setRendering(false);
    }).catch(() => { if (!disposed) setError("这一页无法显示，请下载查看。"); });
    return () => { disposed = true; task?.cancel(); };
  }, [document, page, width]);
  return <div ref={host}>
    {error ? <Failure message={error} resource={resource} retry={retry} /> : <>
      <div className={styles.tools}>
        <button type="button" disabled={!document || page === 1} onClick={() => setPage(page - 1)}>上一页</button>
        <span aria-live="polite">第 {page} 页 / {document?.numPages ?? "…"} 页</span>
        <button type="button" disabled={!document || page >= document.numPages} onClick={() => setPage(page + 1)}>下一页</button>
      </div>
      {!document || rendering ? <p className={styles.hint} role="status">正在渲染 PDF…</p> : null}
      <canvas ref={canvas} className={styles.pdfCanvas} role="img" aria-label={`PDF 第 ${page} 页`} />
    </>}
  </div>;
}

function TextPreview({ bytes, resource }: { bytes: Uint8Array; resource: PreviewResource }) {
  const markdown = previewKind(resource) === "markdown";
  const [source, setSource] = useState(false);
  const [highlighted, setHighlighted] = useState<string | null>(null);
  const [copyStatus, setCopyStatus] = useState("");
  const text = new TextDecoder().decode(bytes);
  const language = previewLanguage(resource.fileName);
  useEffect(() => {
    let disposed = false;
    if (language && text.length <= 200_000) void import("highlight.js/lib/common").then(({ default: hljs }) => {
      if (!disposed && hljs.getLanguage(language)) setHighlighted(hljs.highlight(text, { language, ignoreIllegals: true }).value);
    }).catch(() => {});
    return () => { disposed = true; };
  }, [language, text]);
  async function copy() {
    try { await navigator.clipboard.writeText(text); setCopyStatus("已复制"); }
    catch { setCopyStatus("复制失败，请选择文字后复制"); }
  }
  if (text.includes("\0")) return <Failure resource={resource} message="文件内容不像纯文本，请下载后使用相应软件打开。" />;
  return <>
    <div className={styles.tools}>
      {markdown ? <button type="button" aria-pressed={source} onClick={() => setSource(!source)}>{source ? "查看排版" : "查看源码"}</button> : null}
      <button type="button" onClick={() => void copy()}>复制内容</button>
      <span role="status">{copyStatus}</span>
    </div>
    {markdown && !source ? <div className={styles.paper}><MarkdownContent content={text} /></div>
      : <pre className={styles.source} tabIndex={0}>{highlighted ? <code dangerouslySetInnerHTML={{ __html: highlighted }} /> : <code>{text}</code>}</pre>}
  </>;
}

function DocxPreview({ bytes, resource, retry }: { bytes: Uint8Array; resource: PreviewResource; retry: () => void }) {
  const [result, setResult] = useState<{ html?: string; error?: string }>({});
  useEffect(() => {
    let disposed = false;
    void Promise.all([import("mammoth"), import("dompurify")]).then(async ([mammoth, { default: purifier }]) => {
      const converted = await mammoth.convertToHtml({ arrayBuffer: bytes.slice().buffer }, {
        externalFileAccess: false,
        convertImage: mammoth.images.imgElement(async image => {
          if (!/^image\/(png|jpeg|gif|webp|bmp)$/.test(image.contentType)) return { src: "", alt: "此图片格式暂不支持预览" };
          return { src: `data:${image.contentType};base64,${await image.read("base64")}` };
        }),
      });
      const clean = purifier.sanitize(converted.value, {
        ALLOWED_TAGS: ["p", "div", "span", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "strong", "b", "em", "i", "u", "s", "sub", "sup", "ul", "ol", "li", "blockquote", "pre", "code", "table", "thead", "tbody", "tr", "th", "td", "a", "img"],
        ALLOWED_ATTR: ["href", "src", "alt", "title", "colspan", "rowspan"],
      });
      const dom = new DOMParser().parseFromString(clean, "text/html");
      dom.querySelectorAll("a").forEach(link => { link.target = "_blank"; link.rel = "noopener noreferrer"; });
      dom.querySelectorAll("img").forEach(img => { if (!/^data:image\/(png|jpeg|gif|webp|bmp);base64,/i.test(img.getAttribute("src") ?? "")) img.removeAttribute("src"); });
      if (!disposed) setResult({ html: dom.body.innerHTML });
    }).catch(() => { if (!disposed) setResult({ error: "DOCX 无法解析，可能已损坏或加密。请下载后使用 Word 打开。" }); });
    return () => { disposed = true; };
  }, [bytes]);
  if (result.error) return <Failure message={result.error} resource={resource} retry={retry} />;
  if (result.html === undefined) return <p className={styles.message} role="status">正在解析文档…</p>;
  return <><p className={styles.hint}>只读预览 · 复杂排版可能与 Word 不同</p><div className={styles.paper} dangerouslySetInnerHTML={{ __html: result.html || "<p>文档中没有可显示的内容。</p>" }} /></>;
}

function LoadedPreview({ resource, retry }: { resource: PreviewResource; retry: () => void }) {
  const kind = previewKind(resource);
  const [data, setData] = useState<Uint8Array | null>(null);
  const [imageUrl, setImageUrl] = useState("");
  const [error, setError] = useState("");
  const [zoom, setZoom] = useState(1);
  const [mediaReady, setMediaReady] = useState(false);
  useEffect(() => {
    if (kind === "unsupported" || kind === "video" || kind === "audio") return;
    const controller = new AbortController();
    let url = "";
    void readPreviewBytes(resource, kind, controller.signal).then(bytes => {
      if (controller.signal.aborted) return;
      if (kind === "image") { url = URL.createObjectURL(new Blob([bytes.slice().buffer], { type: resource.mimeType })); setImageUrl(url); }
      else setData(bytes);
    }).catch(failure => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : "文件加载失败，请重试。"); });
    return () => { controller.abort(); if (url) URL.revokeObjectURL(url); };
  }, [kind, resource]);
  if (kind === "unsupported") return <Failure resource={resource} message="此格式暂不支持预览，你可以下载后使用相应软件打开。" />;
  if (error) return <Failure resource={resource} message={error} retry={retry} />;
  if (kind === "video" || kind === "audio") return <>
    {!mediaReady ? <p className={styles.hint} role="status">正在加载媒体…</p> : null}
    <div className={styles.media}>{kind === "video" ? <video src={resource.viewUrl} controls playsInline preload="metadata" onLoadedMetadata={() => setMediaReady(true)} onError={() => setError("视频无法播放，文件可能不可用或编码不受浏览器支持。")} />
      : <audio src={resource.viewUrl} controls preload="metadata" onLoadedMetadata={() => setMediaReady(true)} onError={() => setError("音频无法播放，文件可能不可用或编码不受浏览器支持。")} />}</div>
  </>;
  if (kind === "image" && imageUrl) return <>
    <div className={styles.tools}><button type="button" disabled={zoom <= .25} onClick={() => setZoom(Math.max(.25, zoom - .25))} aria-label="缩小图片">−</button>
      <span>{Math.round(zoom * 100)}%</span><button type="button" disabled={zoom >= 4} onClick={() => setZoom(Math.min(4, zoom + .25))} aria-label="放大图片">＋</button>
      <button type="button" onClick={() => setZoom(1)}>适应宽度</button></div>
    <div className={`${styles.media} ${styles.imageMedia}`} tabIndex={0} aria-label="图片查看区域"><picture style={{ width: `${zoom * 100}%`, margin: "auto" }}><img src={imageUrl} alt={resource.fileName} style={{ width: "100%" }} onError={() => setError("图片无法解码，请下载查看。")} /></picture></div>
  </>;
  if (!data) return <p className={styles.message} role="status">正在加载文件…</p>;
  if (kind === "pdf") return <PdfPreview bytes={data} resource={resource} retry={retry} />;
  if (kind === "docx") return <DocxPreview bytes={data} resource={resource} retry={retry} />;
  return <TextPreview bytes={data} resource={resource} />;
}

export default function FilePreviewContent({ resource }: { resource: PreviewResource }) {
  const [attempt, setAttempt] = useState(0);
  return <LoadedPreview key={attempt} resource={resource} retry={() => setAttempt(attempt + 1)} />;
}

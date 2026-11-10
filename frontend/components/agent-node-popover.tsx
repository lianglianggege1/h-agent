"use client";

import { useEffect, useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";
import type { TopologyEntry } from "@/lib/agent-topology";
import { AgentNodeDetails } from "./agent-node-details";
import styles from "./agent-node-popover.module.css";

export function AgentNodePopover({ anchor, entry, entries, stateKeys, onSelect, onClose }: {
  anchor: HTMLElement; entry: TopologyEntry; entries: TopologyEntry[];
  stateKeys: Array<{ key: string; type: string }>; onSelect: (path: string) => void; onClose: () => void;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    function position() {
      const element = panel.current;
      if (!element) return;
      const narrow = window.matchMedia("(max-width: 600px)").matches;
      element.setAttribute("aria-modal", String(narrow));
      if (narrow) return;
      const rect = anchor.getBoundingClientRect();
      const bounds = element.getBoundingClientRect();
      const right = rect.right + 12;
      const left = right + bounds.width <= window.innerWidth - 12 ? right : rect.left - bounds.width - 12;
      element.style.left = `${Math.max(12, Math.min(left, window.innerWidth - bounds.width - 12))}px`;
      element.style.top = `${Math.max(12, Math.min(rect.top, window.innerHeight - bounds.height - 12))}px`;
    }
    position();
    const observer = new ResizeObserver(position);
    if (panel.current) observer.observe(panel.current);
    window.addEventListener("resize", position);
    window.addEventListener("scroll", position, true);
    return () => { observer.disconnect(); window.removeEventListener("resize", position); window.removeEventListener("scroll", position, true); };
  }, [anchor, entry]);

  useEffect(() => {
    panel.current?.scrollTo({ top: 0 });
    panel.current?.querySelector<HTMLElement>('[aria-label="关闭节点详情"]')?.focus({ preventScroll: true });
  }, [entry.path]);

  useEffect(() => {
    const element = panel.current;
    function outside(event: PointerEvent) {
      if (event.target instanceof Node && !element?.contains(event.target) && !anchor.contains(event.target)) onClose();
    }
    function keydown(event: KeyboardEvent) {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); onClose(); }
      if (event.key !== "Tab" || !window.matchMedia("(max-width: 600px)").matches) return;
      const controls = Array.from(element?.querySelectorAll<HTMLElement>('button:not([disabled]), a[href], [tabindex="0"]') ?? []);
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("keydown", keydown);
    return () => {
      document.removeEventListener("pointerdown", outside, true);
      document.removeEventListener("keydown", keydown);
      if (anchor.isConnected && (element?.contains(document.activeElement) || document.activeElement === document.body)) anchor.focus({ preventScroll: true });
    };
  }, [anchor, onClose]);

  return createPortal(<div className={`resource-page ${styles.layer}`}>
    <div ref={panel} id="agent-node-popover" role="dialog" aria-label={`节点详情：${entry.node.name || entry.node.nodeId}`} className={styles.popover}>
      <AgentNodeDetails entry={entry} entries={entries} stateKeys={stateKeys} onSelect={onSelect} onClose={onClose} />
    </div>
  </div>, document.body);
}

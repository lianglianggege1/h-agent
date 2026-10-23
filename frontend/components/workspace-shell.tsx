"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { createContext, useContext, useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { workspaceNavigation, workspaceSection } from "@/lib/workspace-navigation";
import styles from "./workspace-shell.module.css";

const SidebarTarget = createContext<{ target: HTMLElement | null; close: () => void; onKeyDown: (event: KeyboardEvent<HTMLElement>) => void } | null>(null);

export function WorkspaceSidebarSlot({ children }: { children: ReactNode }) {
  const sidebar = useContext(SidebarTarget);
  return sidebar?.target ? createPortal(<div className={styles.slotContent} onKeyDown={sidebar.onKeyDown} onClick={(event) => {
    if ((event.target as HTMLElement).closest("[data-close-navigation]")) sidebar.close();
  }}>{children}</div>, sidebar.target) : null;
}

export function WorkspaceIcon({ name }: { name: string }) {
  const paths: Record<string, ReactNode> = {
    chat: <path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l-3 3V11.5A7.5 7.5 0 0 1 9.5 4h3a7.5 7.5 0 0 1 7.5 7.5Z" />,
    agents: <><rect x="4" y="6" width="16" height="14" rx="4" /><path d="M12 2v4M2 12h2m16 0h2M8 15h8M8 10h.01M16 10h.01" /></>,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    phone: <path d="m7 3 3 5-2 2a14 14 0 0 0 6 6l2-2 5 3-1 4C10 22 2 14 3 4l4-1Z" />,
    prompt: <><rect x="4" y="3" width="16" height="18" rx="3" /><path d="m8 8 3 3-3 3m5 2h3" /></>,
    book: <><path d="M12 5v16M3 4h5a4 4 0 0 1 4 3 4 4 0 0 1 4-3h5v15h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3Z" /></>,
    skills: <path d="m13 2-9 12h7l-1 8 10-12h-7l0-8Z" />,
    memory: <><rect x="5" y="5" width="14" height="14" rx="4" /><path d="M9 9h6v6H9zM9 2v3m6-3v3M9 19v3m6-3v3M2 9h3m-3 6h3m14-6h3m-3 6h3" /></>,
    settings: <><circle cx="12" cy="8" r="4" /><path d="M4 21v-2a8 8 0 0 1 16 0v2" /></>,
    panel: <><rect x="3" y="4" width="18" height="16" rx="3" /><path d="M9 4v16" /></>,
    menu: <path d="M4 6h16M4 12h16M4 18h16" />,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    plus: <path d="M12 5v14M5 12h14" />,
  };
  return <svg aria-hidden="true" width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">{paths[name] ?? paths.chat}</svg>;
}

export function WorkspaceShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const section = workspaceSection(pathname);
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [sidebarTarget, setSidebarTarget] = useState<HTMLElement | null>(null);
  const sidebar = useRef<HTMLElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const desktop = window.matchMedia("(min-width: 1024px)");
    const onResize = () => { if (desktop.matches) setMobileOpen(false); };
    desktop.addEventListener("change", onResize);
    return () => desktop.removeEventListener("change", onResize);
  }, []);

  useEffect(() => {
    if (!mobileOpen) return;
    const previous = document.activeElement as HTMLElement | null;
    const trigger = menuButton.current;
    sidebar.current?.querySelector<HTMLButtonElement>("button")?.focus();
    return () => { (previous?.isConnected ? previous : trigger)?.focus(); };
  }, [mobileOpen]);

  function navigate(event: React.MouseEvent<HTMLAnchorElement>) {
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (event.currentTarget.pathname === window.location.pathname && !event.currentTarget.search) {
      setMobileOpen(false);
      return;
    }
    // Let editors veto sidebar navigation using their existing unsaved-change check.
    if (!window.dispatchEvent(new Event("h-agent:before-navigate", { cancelable: true }))) {
      event.preventDefault();
      return;
    }
    setMobileOpen(false);
  }

  function sidebarKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (!mobileOpen) return;
    if (event.key === "Escape") { setMobileOpen(false); return; }
    if (event.key !== "Tab") return;
    const controls = Array.from(sidebar.current?.querySelectorAll<HTMLElement>("a[href], button:not(:disabled), input") ?? []).filter((item) => item.getClientRects().length > 0);
    const first = controls[0], last = controls.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }

  if (!section) return children;

  return <SidebarTarget.Provider value={{ target: sidebarTarget, close: () => setMobileOpen(false), onKeyDown: sidebarKeyDown }}>
    <div className={`${styles.shell} ${collapsed ? styles.collapsed : ""} ${mobileOpen ? styles.mobileOpen : ""}`}>
      <a href="#workspace-main" className={styles.skip}>跳到主要内容</a>
      {mobileOpen ? <button className={styles.overlay} aria-label="关闭导航遮罩" onClick={() => setMobileOpen(false)} tabIndex={-1} /> : null}
      <aside id="workspace-sidebar" ref={sidebar} className={styles.sidebar} aria-label="工作区侧栏" role={mobileOpen ? "dialog" : undefined} aria-modal={mobileOpen || undefined}
        onKeyDown={sidebarKeyDown}>
        <div className={styles.brand}>
          <Link href="/chat" onClick={navigate} className={styles.brandLink} aria-label="H-Agent 工作区"><span className={styles.logo}>H</span><span className={styles.label}>H-Agent<span className={styles.brandCaption}>你的 AI 工作空间</span></span></Link>
          <button className={`${styles.iconButton} ${styles.desktopToggle}`} title={collapsed ? "展开侧栏" : "收起侧栏"} aria-label={collapsed ? "展开侧栏" : "收起侧栏"} aria-expanded={!collapsed} onClick={() => setCollapsed(!collapsed)}><WorkspaceIcon name="panel" /></button>
          <button className={`${styles.iconButton} ${styles.mobileClose}`} aria-label="关闭导航" onClick={() => setMobileOpen(false)}><WorkspaceIcon name="close" /></button>
        </div>
        <nav className={styles.navigation} aria-label="主导航">
          {["工作区", "资源"].map((group) => <div key={group} className={styles.group}>
            <p className={styles.groupLabel}>{group}</p>
            {workspaceNavigation.filter((item) => item.group === group).map((item) => <Link key={item.href} href={item.href} onClick={navigate} title={item.label} aria-current={section.href === item.href ? "page" : undefined} className={`${styles.navLink} ${section.href === item.href ? styles.active : ""}`}><WorkspaceIcon name={item.icon} /><span className={styles.label}>{item.label}</span></Link>)}
          </div>)}
        </nav>
        <div className={styles.sidebarSlot} ref={setSidebarTarget} />
        <div className={styles.account}><Link href="/me" title="个人设置" onClick={navigate} aria-current={pathname === "/me" ? "page" : undefined} className={`${styles.navLink} ${pathname === "/me" ? styles.active : ""}`}><WorkspaceIcon name="settings" /><span className={styles.label}>个人设置</span><span className={`${styles.accountHint} ${styles.label}`}>↗</span></Link></div>
      </aside>
      <div className={styles.body} inert={mobileOpen || undefined}>
        <div className={styles.mobileBar}><button ref={menuButton} className={styles.iconButton} aria-label="打开导航" aria-expanded={mobileOpen} aria-controls="workspace-sidebar" onClick={() => setMobileOpen(true)}><WorkspaceIcon name="menu" /></button><Link href="/chat">H-Agent</Link><span>{section.label}</span></div>
        <div id="workspace-main" tabIndex={-1} className={styles.content}>{children}</div>
      </div>
    </div>
  </SidebarTarget.Provider>;
}

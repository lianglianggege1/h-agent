"use client";

import { useState, type ReactNode } from "react";
import styles from "./auth-form.module.css";

export function AuthFormLayout({ title, description, children, footer }: { title: string; description: string; children: ReactNode; footer: ReactNode }) {
  return <main className={styles.page}>
    <section className={styles.panel} aria-labelledby="auth-title">
      <div className={styles.brand}><span className={styles.logo} aria-hidden="true">H</span><span>H-Agent</span></div>
      <header className={styles.intro}><h1 id="auth-title">{title}</h1><p>{description}</p></header>
      {children}
      <div className={styles.footer}>{footer}</div>
    </section>
  </main>;
}

export function PasswordField({ id, label, value, onChange, autoComplete = "current-password", placeholder = "输入密码" }: {
  id: string; label: string; value: string; onChange: (value: string) => void;
  autoComplete?: "current-password" | "new-password"; placeholder?: string;
}) {
  const [visible, setVisible] = useState(false);
  return <div className={styles.field}>
    <label htmlFor={id}>{label}</label>
    <div className={styles.password}>
      <input id={id} name={id} type={visible ? "text" : "password"} value={value} onChange={event => onChange(event.target.value)} autoComplete={autoComplete} placeholder={placeholder} />
      <button type="button" aria-label={`${visible ? "隐藏" : "显示"}${label}`} aria-pressed={visible} aria-controls={id} onClick={() => setVisible(current => !current)}>{visible ? "隐藏" : "显示"}</button>
    </div>
  </div>;
}

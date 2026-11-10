"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { AuthFormLayout, PasswordField } from "@/components/auth/auth-form";
import styles from "@/components/auth/auth-form.module.css";
import { SliderCaptchaDialog } from "@/components/auth/slider-captcha-dialog";
import { register } from "@/lib/auth";

export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [captchaOpen, setCaptchaOpen] = useState(false);

  async function submitWithProof(captchaProof: string) {
    setSubmitting(true);
    try {
      const user = await register({ email: email.trim(), password, captchaProof });
      setMessage(`注册成功：${user.email}`);
      window.setTimeout(() => router.replace("/auth/login"), 600);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "注册失败");
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");

    if (submitting || captchaOpen) return;

    if (!email.trim() || !password || !confirmPassword) {
      setMessage("请填写邮箱和密码");
      return;
    }
    if (password.length < 8) {
      setMessage("密码至少 8 位");
      return;
    }
    if (password !== confirmPassword) {
      setMessage("两次输入的密码不一致");
      return;
    }

    setCaptchaOpen(true);
  }

  return <AuthFormLayout title="创建账号" description="使用邮箱和密码注册 H-Agent。" footer={<>已有账号？<Link href="/auth/login">登录</Link></>}>
    <form onSubmit={handleSubmit}>
      <div className={styles.field}>
        <label htmlFor="email">邮箱</label>
        <input id="email" name="email" type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={event => setEmail(event.target.value)} />
      </div>
      <PasswordField id="password" label="密码" value={password} onChange={setPassword} autoComplete="new-password" placeholder="至少 8 位" />
      <PasswordField id="confirm-password" label="确认密码" value={confirmPassword} onChange={setConfirmPassword} autoComplete="new-password" placeholder="再次输入密码" />
      {message ? <p className={styles.notice} role="alert">{message}</p> : null}
      <button className={styles.submit} type="submit" disabled={submitting || captchaOpen}>{submitting ? "注册中…" : "创建账号"}</button>
    </form>
    <SliderCaptchaDialog open={captchaOpen} purpose="REGISTER" email={email.trim()}
      onVerified={captchaProof => { setCaptchaOpen(false); void submitWithProof(captchaProof); }} onCancel={() => setCaptchaOpen(false)} />
  </AuthFormLayout>;
}

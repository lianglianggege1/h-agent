"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { AuthFormLayout, PasswordField } from "@/components/auth/auth-form";
import styles from "@/components/auth/auth-form.module.css";
import { SliderCaptchaDialog } from "@/components/auth/slider-captcha-dialog";
import { getCurrentUser, login } from "@/lib/auth";
import { consumePostLoginRedirect } from "@/lib/session";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [captchaOpen, setCaptchaOpen] = useState(false);

  useEffect(() => {
    getCurrentUser()
      .then(() => router.replace("/chat"))
      .catch(() => undefined);
  }, [router]);

  async function submitWithProof(captchaProof: string) {
    setSubmitting(true);
    try {
      const result = await login({ email: email.trim(), password, captchaProof });
      const redirectPath = consumePostLoginRedirect() ?? "/chat";
      setMessage(`登录成功，欢迎 ${result.user.email}`);
      router.replace(redirectPath);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");

    if (submitting || captchaOpen) return;

    if (!email.trim() || !password) {
      setMessage("请填写邮箱和密码");
      return;
    }

    setCaptchaOpen(true);
  }

  return <AuthFormLayout title="登录" description="登录你的 H-Agent 工作区。" footer={<>还没有账号？<Link href="/auth/register">创建账号</Link></>}>
    <form onSubmit={handleSubmit}>
      <div className={styles.field}>
        <label htmlFor="email">邮箱</label>
        <input id="email" name="email" type="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={event => setEmail(event.target.value)} />
      </div>
      <PasswordField id="password" label="密码" value={password} onChange={setPassword} />
      {message ? <p className={styles.notice} role="alert">{message}</p> : null}
      <button className={styles.submit} type="submit" disabled={submitting || captchaOpen}>{submitting ? "登录中…" : "登录"}</button>
    </form>
    <SliderCaptchaDialog open={captchaOpen} purpose="LOGIN" email={email.trim()}
      onVerified={captchaProof => { setCaptchaOpen(false); void submitWithProof(captchaProof); }} onCancel={() => setCaptchaOpen(false)} />
  </AuthFormLayout>;
}

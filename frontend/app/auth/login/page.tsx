"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { login } from "@/lib/auth";
import { consumePostLoginRedirect, saveAccessToken } from "@/lib/session";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMessage("");

    if (!email.trim() || !password) {
      setMessage("请填写邮箱和密码");
      return;
    }

    setSubmitting(true);
    try {
      const result = await login({ email: email.trim(), password });
      saveAccessToken(result.accessToken);
      const redirectPath = consumePostLoginRedirect() ?? "/chat";
      setMessage(`登录成功，欢迎 ${result.user.email}`);
      router.replace(redirectPath);
    } catch (error) {
      setMessage(error instanceof Error ? error.message : "登录失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#f3efe2_35%,#ebe6d8_100%)] px-5 py-10 text-stone-900">
      <section className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-md flex-col justify-center">
        <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-6 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-sm font-medium uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight">邮箱登录</h1>
          <p className="mt-2 text-sm leading-6 text-stone-500">登录后即可进入独立 AI 对话页。</p>

          <form className="mt-8 space-y-5" onSubmit={handleSubmit}>
            <label className="block">
              <span className="text-sm font-medium text-stone-700">邮箱</span>
              <input
                className="mt-2 w-full rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 text-base outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="user@example.com"
                autoComplete="email"
              />
            </label>

            <label className="block">
              <span className="text-sm font-medium text-stone-700">密码</span>
              <input
                className="mt-2 w-full rounded-2xl border border-stone-200 bg-stone-50/60 px-4 py-3 text-base outline-none transition focus:border-amber-500 focus:ring-4 focus:ring-amber-100"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="至少 8 位"
                autoComplete="current-password"
              />
            </label>

            {message ? <p className="rounded-2xl bg-stone-100 px-4 py-3 text-sm text-stone-700">{message}</p> : null}

            <button
              className="w-full rounded-2xl bg-stone-900 px-4 py-3 text-base font-semibold text-white transition hover:bg-stone-800 disabled:cursor-not-allowed disabled:bg-stone-400"
              type="submit"
              disabled={submitting}
            >
              {submitting ? "登录中..." : "登录"}
            </button>
          </form>

          <p className="mt-6 text-center text-sm text-stone-500">
            还没有账号？
            <Link className="font-medium text-amber-700" href="/auth/register">
              去注册
            </Link>
          </p>
        </div>
      </section>
    </main>
  );
}

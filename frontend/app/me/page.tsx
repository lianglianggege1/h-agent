"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthUser, getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";

export default function MePage() {
  const router = useRouter();
  const [user, setUser] = useState<AuthUser | null>(null);

  useEffect(() => {
    getCurrentUser()
      .then(setUser)
      .catch(() => {
        savePostLoginRedirect("/me");
        router.replace("/auth/login");
      });
  }, [router]);

  async function handleLogout() {
    try {
      await logout();
    } finally {
      savePostLoginRedirect("/chat");
      router.replace("/auth/login");
    }
  }

  if (!user) {
    return <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)]" />;
  }

  return (
    <main className="min-h-screen bg-[linear-gradient(180deg,#f7f4ea_0%,#efe8d7_100%)] px-4 py-6 text-stone-900">
      <section className="mx-auto w-full max-w-md space-y-5">
        <header className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-5 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h1 className="mt-2 text-2xl font-semibold">我的</h1>
          <p className="mt-3 text-sm text-stone-500">{user.email}</p>
        </header>

        <div className="rounded-[2rem] border border-stone-200/80 bg-white/90 p-4 shadow-[0_24px_60px_rgba(76,59,36,0.12)] backdrop-blur">
          <Link className="block rounded-2xl bg-stone-900 px-4 py-4 text-sm font-semibold text-white" href="/me/system-prompts">
            SystemPrompt 管理
          </Link>
          <Link className="mt-3 block rounded-2xl border border-stone-200 px-4 py-4 text-sm font-semibold text-stone-700" href="/me/knowledge">
            知识库管理
          </Link>
          <Link className="mt-3 block rounded-2xl border border-stone-200 px-4 py-4 text-sm font-semibold text-stone-700" href="/me/agents">
            领域 Agent 管理
          </Link>
          <Link className="mt-3 block rounded-2xl border border-stone-200 px-4 py-4 text-sm font-semibold text-stone-700" href="/chat">
            返回聊天
          </Link>
          <button
            className="mt-3 w-full rounded-2xl border border-stone-200 px-4 py-4 text-left text-sm font-semibold text-stone-700"
            type="button"
            onClick={handleLogout}
          >
            退出登录
          </button>
        </div>
      </section>
    </main>
  );
}

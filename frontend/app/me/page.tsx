"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AuthUser, getCurrentUser, logout } from "@/lib/auth";
import { savePostLoginRedirect } from "@/lib/session";
import { WorkspaceIcon } from "@/components/workspace-shell";

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
    return <main className="workspace-page" />;
  }

  return (
    <main className="workspace-page text-stone-900">
      <section className="workspace-container space-y-5">
        <header className="rounded-2xl border border-stone-200/80 bg-white p-5 shadow-sm backdrop-blur">
          <p className="text-xs uppercase tracking-[0.28em] text-amber-700">H-Agent</p>
          <h1 className="mt-2 text-2xl font-semibold">个人设置</h1>
          <p className="mt-3 text-sm text-stone-500">让助手更了解你，也更适合你的工作。</p>
        </header>

        <div className="flex items-center justify-between gap-4 rounded-2xl border border-stone-200 bg-white p-5">
          <div className="min-w-0"><p className="text-sm font-semibold">账号</p><p className="mt-1 break-all text-sm text-stone-500">{user.email}</p></div>
          <button className="agui-button shrink-0" type="button" onClick={handleLogout}>退出登录</button>
        </div>
        <div className="pt-3"><h2 className="text-sm font-semibold">工作区设置</h2><p className="mt-1 text-xs text-stone-500">管理助手的能力、资源和工作方式。</p></div>
        <div className="workspace-card-grid">
          {[
            { href: "/me/system-prompts", icon: "prompt", title: "提示词", description: "设置助手角色、指令和默认提示词" },
            { href: "/me/knowledge", icon: "book", title: "知识库", description: "上传资料，为助手提供专属知识" },
            { href: "/me/agents", icon: "agents", title: "智能体管理", description: "查看编排拓扑，管理子智能体定义" },
            { href: "/me/skills", icon: "skills", title: "我的技能", description: "编辑技能草稿，管理发布与生效版本" },
            { href: "/automations", icon: "clock", title: "自动化任务", description: "管理定时任务、执行记录和待确认提案" },
            { href: "/me/memory", icon: "memory", title: "长期记忆", description: "查看和编辑跨会话共享的长期记忆" },
            { href: "/outbound", icon: "phone", title: "外呼任务", description: "导入授权名单，管理软电话外呼" },
          ].map((item) => <Link key={item.href} href={item.href} className="group rounded-2xl border border-stone-200 bg-white p-6 transition hover:border-stone-400">
            <div className="mb-5 flex items-center justify-between"><span className="rounded-xl bg-stone-100 p-2.5 text-stone-600"><WorkspaceIcon name={item.icon} /></span><span className="text-stone-400 transition group-hover:translate-x-1">↗</span></div>
            <h3 className="text-sm font-semibold">{item.title}</h3><p className="mt-2 text-xs leading-5 text-stone-500">{item.description}</p>
          </Link>)}
        </div>
      </section>
    </main>
  );
}

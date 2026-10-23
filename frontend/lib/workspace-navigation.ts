export const workspaceNavigation = [
  { href: "/chat", label: "聊天", icon: "chat", group: "工作区", paths: ["/chat", "/call"] },
  { href: "/agents", label: "智能体", icon: "agents", group: "工作区", paths: ["/agents", "/me/agents"] },
  { href: "/automations", label: "自动化任务", icon: "clock", group: "工作区", paths: ["/automations"] },
  { href: "/outbound", label: "外呼任务", icon: "phone", group: "工作区", paths: ["/outbound"] },
  { href: "/me/system-prompts", label: "提示词", icon: "prompt", group: "资源", paths: ["/me/system-prompts"] },
  { href: "/me/knowledge", label: "知识库", icon: "book", group: "资源", paths: ["/me/knowledge"] },
  { href: "/me/skills", label: "技能", icon: "skills", group: "资源", paths: ["/me/skills"] },
  { href: "/me/memory", label: "长期记忆", icon: "memory", group: "资源", paths: ["/me/memory"] },
] as const;

const accountSection = { href: "/me", label: "个人设置", icon: "settings", group: "账号", paths: ["/me"] } as const;

export function workspaceSection(pathname: string) {
  if (pathname === "/me") return accountSection;
  return workspaceNavigation.find((item) => item.paths.some((path) => pathname === path || pathname.startsWith(path + "/")));
}

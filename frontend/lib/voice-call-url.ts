export function buildCallHref(sessionId: string) {
  return "/call?" + new URLSearchParams({ sessionId });
}

export function originalChatHref(sessionId: string) {
  return "/chat?" + new URLSearchParams({ agentId: "standard-chat", sessionId });
}

package com.h.backend.chat.domain.agent;

/** 子 Agent 每一轮都必须恢复的稳定会话身份与父委托。 */
public record HarnessSubagentContext(
        String agentId,
        String userId,
        String parentSessionId,
        String sessionId,
        String assignment
) {
}

package com.h.backend.chat.application;

/**
 * 由实际 Agent Session 解析出的 Harness 执行地址。
 *
 * @param rootSessionId 所属顶级聊天会话
 * @param sessionId 本次实际执行、写消息和加锁的 Agent Session
 * @param gatewaySubagentId Gateway 内部寻址句柄；顶级 Harness 会话为空
 * @param parentSessionId 子 Agent 的直接父 Session；顶级 Harness 会话为空
 * @param subagentAgentId 子 Agent 类型；顶级 Harness 会话为空
 * @param assignment 父 Agent 的原始委托；顶级 Harness 会话为空
 */
public record HarnessExecutionSession(
        String rootSessionId,
        String sessionId,
        String gatewaySubagentId,
        String parentSessionId,
        String subagentAgentId,
        String assignment
) {
    public boolean subagent() {
        return gatewaySubagentId != null;
    }
}

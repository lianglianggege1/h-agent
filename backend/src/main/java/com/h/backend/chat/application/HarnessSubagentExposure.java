package com.h.backend.chat.application;

/** AgentScope exposure 事件规范化后的协作者身份、直接父节点和展示信息。 */
public record HarnessSubagentExposure(
        String gatewaySubagentId,
        String agentId,
        String parentSessionId,
        String sessionId,
        String displayName,
        String assignment
) {
}

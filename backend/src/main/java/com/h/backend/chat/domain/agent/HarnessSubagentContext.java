package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;

/**
 * 子 Agent 每一轮都必须恢复的稳定会话身份与父委托。
 *
 * @param definitionBinding Catalog 子会话在 exposure 时固定到的定义版本；
 *                          声明式/内置子 Agent 为 null，走父静态 factory
 */
public record HarnessSubagentContext(
        String agentId,
        String userId,
        String parentSessionId,
        String sessionId,
        String assignment,
        String executionId,
        DefinitionBinding definitionBinding
) {

    /** 兼容声明式/内置子 Agent 的无绑定构造。 */
    public HarnessSubagentContext(
            String agentId,
            String userId,
            String parentSessionId,
            String sessionId,
            String assignment,
            String executionId
    ) {
        this(agentId, userId, parentSessionId, sessionId, assignment, executionId, null);
    }
}

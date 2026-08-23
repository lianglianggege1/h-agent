package com.h.backend.chat.application;

import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;

/**
 * AgentScope exposure 事件规范化后的协作者身份、直接父节点和展示信息。
 *
 * @param definitionBinding Catalog 定义的 (definitionId, version)，由父 turn snapshot
 *                          解析而来；声明式/内置子 Agent 为 null
 */
public record HarnessSubagentExposure(
        String gatewaySubagentId,
        String agentId,
        String parentSessionId,
        String sessionId,
        String displayName,
        String assignment,
        DefinitionBinding definitionBinding
) {

    /** 兼容声明式/内置子 Agent 的无绑定构造。 */
    public HarnessSubagentExposure(
            String gatewaySubagentId,
            String agentId,
            String parentSessionId,
            String sessionId,
            String displayName,
            String assignment
    ) {
        this(gatewaySubagentId, agentId, parentSessionId, sessionId, displayName, assignment, null);
    }
}

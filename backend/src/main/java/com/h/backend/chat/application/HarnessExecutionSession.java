package com.h.backend.chat.application;

import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;

/**
 * 由实际 Agent Session 解析出的 Harness 执行地址。
 *
 * @param rootSessionId 所属顶级聊天会话
 * @param sessionId 本次实际执行、写消息和加锁的 Agent Session
 * @param gatewaySubagentId Gateway 内部寻址句柄；顶级 Harness 会话为空
 * @param parentSessionId 子 Agent 的直接父 Session；顶级 Harness 会话为空
 * @param subagentAgentId 子 Agent 类型；顶级 Harness 会话为空
 * @param assignment 父 Agent 的原始委托；顶级 Harness 会话为空
 * @param definitionBinding Catalog 子会话在 exposure 时固定的定义版本；
 *                          顶级会话与声明式子 Agent 为空
 */
public record HarnessExecutionSession(
        String rootSessionId,
        String sessionId,
        String gatewaySubagentId,
        String parentSessionId,
        String subagentAgentId,
        String assignment,
        DefinitionBinding definitionBinding
) {
    public boolean subagent() {
        return gatewaySubagentId != null;
    }

    /** 兼容无 Catalog 绑定（顶级会话与声明式子 Agent）的构造。 */
    public HarnessExecutionSession(
            String rootSessionId,
            String sessionId,
            String gatewaySubagentId,
            String parentSessionId,
            String subagentAgentId,
            String assignment
    ) {
        this(rootSessionId, sessionId, gatewaySubagentId, parentSessionId,
                subagentAgentId, assignment, null);
    }
}

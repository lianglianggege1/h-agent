package com.h.backend.chat.domain.approval;

import com.h.backend.chat.domain.agent.ChatAgentIds;

/** 用户为 Harness Agent Session 选择的工具审批策略。 */
public enum ApprovalMode {
    DEFAULT,
    ACCEPT_EDITS,
    EXPLORE,
    BYPASS,
    DONT_ASK;

    /**
     * 规范化新会话的批准模式。
     *
     * <p>旧客户端没有该字段，因此 Harness 缺省保持升级前的自动执行语义；新版客户端必须
     * 显式发送它默认选中的 {@link #DEFAULT}。非 Harness Agent 不拥有批准模式。</p>
     */
    public static ApprovalMode resolveForNewSession(String agentId, ApprovalMode requested) {
        if (!ChatAgentIds.HARNESS.equals(agentId)) {
            if (requested != null) {
                throw new IllegalArgumentException("批准模式只适用于 Harness Agent");
            }
            return null;
        }
        return requested == null ? BYPASS : requested;
    }
}

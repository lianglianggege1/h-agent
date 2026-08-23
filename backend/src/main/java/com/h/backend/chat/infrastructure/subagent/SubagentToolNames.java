package com.h.backend.chat.infrastructure.subagent;

/**
 * AgentScope Subagent 工具名常量。
 *
 * <p>设计 8.2：父 Agent 只保留 {@code agent_spawn}；{@code agent_send} / {@code agent_list}
 * 因共享 key/label Map 的跨用户风险被平台 DENY，管理面与 prompt 替换段统一引用这里。</p>
 */
public final class SubagentToolNames {

    public static final String AGENT_SPAWN = "agent_spawn";
    public static final String AGENT_SEND = "agent_send";
    public static final String AGENT_LIST = "agent_list";

    private SubagentToolNames() {
    }
}

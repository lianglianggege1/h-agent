package com.h.backend.memory.domain;

import java.util.Set;

/**
 * 每个稳定逻辑 Agent 显式声明的记忆参与策略。
 * automaticCaptureScope 为 null 表示不自动 capture；一个完成 turn 最多按一个 scope 自动 capture。
 */
public record AgentMemoryPolicy(
        Set<MemoryScopeKind> recallScopes,
        MemoryScopeKind automaticCaptureScope,
        boolean explicitMemoryToolsEnabled
) {

    public AgentMemoryPolicy {
        recallScopes = recallScopes == null ? Set.of() : Set.copyOf(recallScopes);
    }

    public static AgentMemoryPolicy disabled() {
        return new AgentMemoryPolicy(Set.of(), null, false);
    }

    public boolean recallEnabled() {
        return !recallScopes.isEmpty();
    }

    public boolean automaticCaptureEnabled() {
        return automaticCaptureScope != null;
    }
}

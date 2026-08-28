package com.h.backend.memory.domain;

/**
 * 显式“记住”。infer=false，获得可核验 memory ID 后才返回成功；
 * 实体 ID 由服务端补齐：AGENT 必填 logicalAgentId，RUN 必填 logicalAgentId + memoryRunId。
 */
public record ExplicitMemorySave(
        Long userId,
        MemoryScopeKind scope,
        String logicalAgentId,
        String memoryRunId,
        String text,
        Long sourceExecutionId
) {
    public ExplicitMemorySave {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (scope != MemoryScopeKind.USER && isBlank(logicalAgentId)) {
            throw new IllegalArgumentException("logicalAgentId is required for " + scope + " scope");
        }
        if (scope == MemoryScopeKind.RUN && isBlank(memoryRunId)) {
            throw new IllegalArgumentException("memoryRunId is required for RUN scope");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.h.backend.memory.domain;

/**
 * 唯一决定哪些身份字段离开项目的实现：调用方总是提交完整身份，
 * 这里按 scope 精确产生 Mem0 user_id/agent_id/run_id，其余字段不外发。
 */
public final class MemoryScopePolicy {

    public static final String USER_ID_PREFIX = "h-agent:user:";
    public static final String AGENT_ID_PREFIX = "h-agent:agent:";
    public static final String RUN_ID_PREFIX = "h-agent:run:";

    private MemoryScopePolicy() {
    }

    public static MemoryOwnerScope toOwnerScope(MemoryInvocationContext context, MemoryScopeKind kind) {
        if (context == null || kind == null) {
            throw new IllegalArgumentException("context and scope kind are required");
        }
        return toOwnerScope(context.userId(), kind, context.logicalAgentId(), context.memoryRunId());
    }

    /** 本地控制记录或显式命令的 scope 还原；USER 的 agent/run 为空，AGENT 的 run 为空。 */
    public static MemoryOwnerScope toOwnerScope(Long userId,
                                                MemoryScopeKind kind,
                                                String logicalAgentId,
                                                String memoryRunId) {
        if (userId == null || kind == null) {
            throw new IllegalArgumentException("userId and scope kind are required");
        }
        String mem0UserId = USER_ID_PREFIX + userId;
        String mem0AgentId = kind == MemoryScopeKind.USER ? null
                : AGENT_ID_PREFIX + logicalAgentId;
        String mem0RunId = kind == MemoryScopeKind.RUN
                ? RUN_ID_PREFIX + memoryRunId
                : null;
        return new MemoryOwnerScope(mem0UserId, mem0AgentId, mem0RunId, kind);
    }

    public record MemoryOwnerScope(
            String mem0UserId,
            String mem0AgentId,
            String mem0RunId,
            MemoryScopeKind scopeKind
    ) {
        public MemoryOwnerScope {
            if (mem0UserId == null || mem0UserId.isBlank()) {
                throw new IllegalArgumentException("mem0UserId is required");
            }
        }
    }
}

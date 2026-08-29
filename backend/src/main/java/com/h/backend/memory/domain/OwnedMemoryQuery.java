package com.h.backend.memory.domain;

/** 用户记忆普通列表查询：按本地索引 owner/scope 分页，再向 Mem0 取当前页正文。 */
public record OwnedMemoryQuery(
        Long userId,
        MemoryScopeKind scopeKind,
        String logicalAgentId,
        String cursor,
        int pageSize
) {
    public OwnedMemoryQuery {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}

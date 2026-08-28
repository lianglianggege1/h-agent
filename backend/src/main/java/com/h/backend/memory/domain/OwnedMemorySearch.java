package com.h.backend.memory.domain;

/** 用户记忆语义搜索：Mem0 返回有序结果，再经本地 owner/state 索引过滤。 */
public record OwnedMemorySearch(
        Long userId,
        String query,
        int limit
) {
    public OwnedMemorySearch {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
    }
}

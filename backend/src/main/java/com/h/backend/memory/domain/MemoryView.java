package com.h.backend.memory.domain;

import java.time.Instant;

/** 用户记忆管理页的单条记忆视图。正文来自 Mem0，本地索引不保存正文。 */
public record MemoryView(
        Long localId,
        String remoteMemoryId,
        MemoryScopeKind scopeKind,
        String logicalAgentId,
        String memoryRunId,
        int version,
        String operationState,
        String text,
        Instant remoteUpdatedAt,
        Instant createdAt
) {
}

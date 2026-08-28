package com.h.backend.memory.domain;

import java.time.Instant;
import java.util.List;

public record MemoryRecallResult(List<MemoryItem> items) {

    public MemoryRecallResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static MemoryRecallResult empty() {
        return new MemoryRecallResult(List.of());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public record MemoryItem(
            String remoteMemoryId,
            String text,
            MemoryScopeKind scopeKind,
            double score,
            Instant updatedAt
    ) {
    }
}

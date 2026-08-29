package com.h.backend.memory.domain;

import java.time.Instant;
import java.util.List;

/** 单条记忆的远程演化历史；由 Mem0 提供，本地不保存副本。 */
public record MemoryHistory(
        Long localId,
        String remoteMemoryId,
        List<Entry> entries
) {
    public MemoryHistory {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public record Entry(
            String text,
            Instant createdAt
    ) {
    }
}

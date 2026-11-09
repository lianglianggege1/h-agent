package com.h.backend.memory.domain;

import java.time.Instant;
import java.util.List;

/** 单条记忆的演化历史；工具记忆使用本地不可变修订，旧记忆可读取远端历史。 */
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

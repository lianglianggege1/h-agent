package com.h.backend.memory.domain;

import java.util.List;

/** 本地索引 cursor 分页结果；cursor 是不透明的下一页游标。 */
public record MemoryPage(
        List<MemoryView> items,
        String nextCursor,
        boolean hasMore
) {
    public MemoryPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static MemoryPage empty() {
        return new MemoryPage(List.of(), null, false);
    }
}

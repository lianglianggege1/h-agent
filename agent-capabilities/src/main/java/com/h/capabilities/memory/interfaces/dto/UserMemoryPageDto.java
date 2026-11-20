package com.h.capabilities.memory.interfaces.dto;

import com.h.capabilities.memory.domain.MemoryPage;

import java.util.List;

public record UserMemoryPageDto(
        List<UserMemoryItemDto> items,
        String nextCursor,
        boolean hasMore
) {
    public static UserMemoryPageDto from(MemoryPage page) {
        return new UserMemoryPageDto(
                page.items().stream().map(UserMemoryItemDto::from).toList(),
                page.nextCursor(),
                page.hasMore()
        );
    }
}

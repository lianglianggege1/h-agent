package com.h.backend.memory.interfaces.dto;

import com.h.backend.memory.domain.MemoryHistory;

import java.time.Instant;
import java.util.List;

public record UserMemoryHistoryDto(
        Long localId,
        String remoteMemoryId,
        List<EntryDto> entries
) {
    public static UserMemoryHistoryDto from(MemoryHistory history) {
        return new UserMemoryHistoryDto(
                history.localId(),
                history.remoteMemoryId(),
                history.entries().stream()
                        .map(entry -> new EntryDto(entry.text(), entry.createdAt()))
                        .toList()
        );
    }

    public record EntryDto(
            String text,
            Instant createdAt
    ) {
    }
}

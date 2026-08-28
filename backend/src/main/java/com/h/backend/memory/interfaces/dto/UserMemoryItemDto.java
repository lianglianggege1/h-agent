package com.h.backend.memory.interfaces.dto;

import com.h.backend.memory.domain.MemoryView;

import java.time.Instant;

public record UserMemoryItemDto(
        Long localId,
        String remoteMemoryId,
        String scopeKind,
        String agentId,
        String runId,
        int version,
        String operationState,
        String text,
        Instant remoteUpdatedAt,
        Instant createdAt
) {
    public static UserMemoryItemDto from(MemoryView view) {
        return new UserMemoryItemDto(
                view.localId(),
                view.remoteMemoryId(),
                view.scopeKind().name(),
                view.logicalAgentId(),
                view.memoryRunId(),
                view.version(),
                view.operationState(),
                view.text(),
                view.remoteUpdatedAt(),
                view.createdAt()
        );
    }
}

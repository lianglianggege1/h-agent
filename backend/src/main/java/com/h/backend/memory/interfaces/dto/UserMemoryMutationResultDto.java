package com.h.backend.memory.interfaces.dto;

import com.h.backend.memory.domain.MemoryMutationResult;

public record UserMemoryMutationResultDto(
        Long localId,
        String remoteMemoryId,
        int version,
        String state,
        String message
) {
    public static UserMemoryMutationResultDto from(MemoryMutationResult result) {
        return new UserMemoryMutationResultDto(
                result.localId(),
                result.remoteMemoryId(),
                result.version(),
                result.state(),
                result.message()
        );
    }
}

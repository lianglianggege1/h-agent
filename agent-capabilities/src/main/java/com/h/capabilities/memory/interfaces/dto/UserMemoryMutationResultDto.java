package com.h.capabilities.memory.interfaces.dto;

import com.h.capabilities.memory.domain.MemoryMutationResult;

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

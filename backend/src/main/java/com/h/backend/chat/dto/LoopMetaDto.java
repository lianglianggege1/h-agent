package com.h.backend.chat.dto;

public record LoopMetaDto(
        Integer maxIterations,
        String exitCondition,
        Boolean testExitAtLoopEnd
) {
}

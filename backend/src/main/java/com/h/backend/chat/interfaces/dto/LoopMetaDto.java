package com.h.backend.chat.interfaces.dto;

public record LoopMetaDto(
        Integer maxIterations,
        String exitCondition,
        Boolean testExitAtLoopEnd
) {
}

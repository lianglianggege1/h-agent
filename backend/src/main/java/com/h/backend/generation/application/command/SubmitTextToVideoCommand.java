package com.h.backend.generation.application.command;

public record SubmitTextToVideoCommand(
        Long userId,
        String sessionId,
        String originalPrompt,
        String submittedPrompt,
        String model,
        Integer durationSeconds,
        String resolution,
        Boolean promptOptimizer,
        Boolean fastPretreatment,
        Boolean aigcWatermark
) {
}

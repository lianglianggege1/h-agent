package com.h.backend.generation.application.command;

public record SubmitImageToVideoCommand(
        Long userId,
        String sessionId,
        String referenceResourceId,
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

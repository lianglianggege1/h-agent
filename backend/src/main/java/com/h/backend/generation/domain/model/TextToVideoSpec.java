package com.h.backend.generation.domain.model;

import java.util.Set;

public record TextToVideoSpec(
        String originalPrompt,
        String submittedPrompt,
        String model,
        int durationSeconds,
        String resolution,
        boolean promptOptimizer,
        boolean fastPretreatment,
        boolean aigcWatermark
) {
    private static final Set<String> HAILUO_MODELS = Set.of("MiniMax-Hailuo-2.3", "MiniMax-Hailuo-02");
    private static final Set<String> LEGACY_MODELS = Set.of("T2V-01-Director", "T2V-01");

    public TextToVideoSpec {
        validatePrompt(originalPrompt, "originalPrompt");
        validatePrompt(submittedPrompt, "submittedPrompt");
        validateModel(model);
        validateFormat(model, durationSeconds, resolution, fastPretreatment);
    }

    public static TextToVideoSpec withDefaults(
            String originalPrompt,
            String submittedPrompt,
            String model,
            Integer durationSeconds,
            String resolution,
            Boolean promptOptimizer,
            Boolean fastPretreatment,
            Boolean aigcWatermark
    ) {
        return new TextToVideoSpec(
                originalPrompt,
                submittedPrompt,
                model == null || model.isBlank() ? "MiniMax-Hailuo-2.3" : model,
                durationSeconds == null ? 6 : durationSeconds,
                resolution == null || resolution.isBlank() ? "768P" : resolution,
                Boolean.TRUE.equals(promptOptimizer),
                Boolean.TRUE.equals(fastPretreatment),
                Boolean.TRUE.equals(aigcWatermark)
        );
    }

    private static void validatePrompt(String prompt, String field) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 2000) {
            throw new IllegalArgumentException(field + " must contain 1 to 2000 characters");
        }
    }

    private static void validateModel(String model) {
        if (!HAILUO_MODELS.contains(model) && !LEGACY_MODELS.contains(model)) {
            throw new IllegalArgumentException("Unsupported MiniMax video model: " + model);
        }
    }

    private static void validateFormat(String model, int durationSeconds, String resolution, boolean fastPretreatment) {
        if (HAILUO_MODELS.contains(model)) {
            if (durationSeconds == 6 && ("768P".equals(resolution) || "1080P".equals(resolution))) {
                return;
            }
            if (durationSeconds == 10 && "768P".equals(resolution)) {
                return;
            }
            throw new IllegalArgumentException("Hailuo supports 6s at 768P/1080P or 10s at 768P");
        }
        if (fastPretreatment) {
            throw new IllegalArgumentException("fastPretreatment is only supported by Hailuo models");
        }
        if (durationSeconds != 6 || !"720P".equals(resolution)) {
            throw new IllegalArgumentException("Legacy models only support 6s at 720P");
        }
    }
}

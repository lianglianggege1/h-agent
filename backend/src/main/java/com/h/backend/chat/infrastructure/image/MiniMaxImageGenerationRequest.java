package com.h.backend.chat.infrastructure.image;

public record MiniMaxImageGenerationRequest(
        String model,
        String prompt,
        String aspectRatio,
        String responseFormat,
        int n,
        boolean promptOptimizer,
        SubjectReference subjectReference
) {
    public record SubjectReference(String type, String imageFile) {
    }
}

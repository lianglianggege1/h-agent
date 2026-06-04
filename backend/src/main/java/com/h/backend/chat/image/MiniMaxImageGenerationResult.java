package com.h.backend.chat.image;

public record MiniMaxImageGenerationResult(
        String providerRequestId,
        String mimeType,
        String model,
        byte[] imageBytes,
        Integer width,
        Integer height,
        String rawResponseJson
) {
}

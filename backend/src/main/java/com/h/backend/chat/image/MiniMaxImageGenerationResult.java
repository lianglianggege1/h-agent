package com.h.backend.chat.image;

import java.util.List;

public record MiniMaxImageGenerationResult(
        String providerRequestId,
        String mimeType,
        String model,
        byte[] imageBytes,
        Integer width,
        Integer height,
        String rawResponseJson,
        List<GeneratedImage> images
) {
    public MiniMaxImageGenerationResult(
            String providerRequestId,
            String mimeType,
            String model,
            byte[] imageBytes,
            Integer width,
            Integer height,
            String rawResponseJson
    ) {
        this(
                providerRequestId,
                mimeType,
                model,
                imageBytes,
                width,
                height,
                rawResponseJson,
                List.of(new GeneratedImage(mimeType, imageBytes, width, height))
        );
    }

    public record GeneratedImage(
            String mimeType,
            byte[] imageBytes,
            Integer width,
            Integer height
    ) {
    }
}

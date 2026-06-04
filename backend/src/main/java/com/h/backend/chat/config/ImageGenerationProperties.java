package com.h.backend.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "image-generation")
public record ImageGenerationProperties(
        MiniMax minimax,
        LocalStorage storage
) {

    public MiniMax minimaxOrDefault() {
        return minimax == null ? new MiniMax(
                "https://api.minimaxi.chat",
                "",
                "image-01",
                "1:1",
                true
        ) : minimax;
    }

    public LocalStorage storageOrDefault() {
        return storage == null ? new LocalStorage("/tmp/h-agent", "") : storage;
    }

    public record MiniMax(
            String baseUrl,
            String apiKey,
            String model,
            String aspectRatio,
            boolean promptOptimizer
    ) {
    }

    public record LocalStorage(
            String baseDir,
            String publicBaseUrl
    ) {
    }
}

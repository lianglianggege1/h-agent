package com.h.backend.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageGenerationPropertiesTest {

    @Test
    void shouldBindStorageAndMiniMaxProperties() {
        Map<String, String> values = Map.of(
                "image-generation.storage.base-dir", "/data/images",
                "image-generation.storage.public-base-url", "https://cdn.example.com",
                "image-generation.minimax.base-url", "https://api.example.com",
                "image-generation.minimax.api-key", "test-key",
                "image-generation.minimax.model", "image-01",
                "image-generation.minimax.aspect-ratio", "16:9",
                "image-generation.minimax.prompt-optimizer", "true",
                "image-generation.minimax.n", "3",
                "image-generation.minimax.request-timeout-seconds", "180"
        );

        ImageGenerationProperties properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("image-generation", ImageGenerationProperties.class)
                .orElseThrow(IllegalStateException::new);

        ImageGenerationProperties.LocalStorage storage = properties.storageOrDefault();
        assertEquals("/data/images", storage.baseDir());
        assertEquals("https://cdn.example.com", storage.publicBaseUrl());

        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        assertEquals("https://api.example.com", minimax.baseUrl());
        assertEquals("test-key", minimax.apiKey());
        assertEquals("image-01", minimax.model());
        assertEquals("16:9", minimax.aspectRatio());
        assertEquals(3, minimax.n());
        assertEquals(180, minimax.requestTimeoutSeconds());
    }
}
